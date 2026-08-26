package com.miit.app.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Complete Xiaomi SPP connection with authenticated post-auth system initialization. */
class XiaomiBandConnection(
    private val device: BluetoothDevice,
    private val authKey: ByteArray,
    private val onEvent: (String)->Unit,
    private val onState: (BandConnectionState)->Unit,
    private val onDataUpdate: (BandDataUpdate)->Unit
) {
    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val V1_REQUEST = byteArrayOf(0xBA.toByte(),0xDC.toByte(),0xFE.toByte(),0,0xC0.toByte(),3,0,0,0,0,0xEF.toByte())
        private const val SESSION=2
        private const val DATA=3
        private const val ACK=1
        private const val SYSTEM_TYPE=2
    }
    @Volatile private var running=false
    @Volatile private var authenticated=false
    private var socket:BluetoothSocket?=null
    private var input:InputStream?=null
    private var output:OutputStream?=null
    private var worker:Thread?=null
    private val lock=Any()
    private val txSeq=AtomicInteger(0)
    private val buffer=ByteArrayOutputStream()
    private var v2=false
    private var auth:Auth?=null

    @SuppressLint("MissingPermission")
    fun connect(){
        if(running)return
        if(authKey.size!=16){onEvent("Xiaomi SPP: auth key invalid; expected 16 bytes");onState(BandConnectionState.Error);return}
        worker=Thread({runConnection()},"Miit-Xiaomi-Band").also{it.start()}
    }
    fun close(){running=false;runCatching{socket?.close()};runCatching{worker?.interrupt()};socket=null;input=null;output=null;worker=null;auth=null;authenticated=false;buffer.reset();txSeq.set(0);v2=false}

    @SuppressLint("MissingPermission")
    private fun runConnection(){
        running=true
        try{
            onState(BandConnectionState.Connecting)
            val s=device.createRfcommSocketToServiceRecord(SPP_UUID);socket=s
            onEvent("Xiaomi SPP: opening RFCOMM socket uuid=$SPP_UUID");s.connect()
            input=s.inputStream;output=s.outputStream;onEvent("Xiaomi SPP: RFCOMM connected")
            send(V1_REQUEST);onEvent("Xiaomi SPP: sent SPPv1 version request")
            val tmp=ByteArray(4096)
            while(running){val n=input?.read(tmp)?:-1;if(n<0)break;if(n>0){buffer.write(tmp,0,n);parseLoop()}}
        }catch(t:Throwable){if(running)onEvent("Xiaomi SPP error: ${t.javaClass.simpleName}: ${t.message?:"unknown"}")}
        finally{val was=authenticated;running=false;runCatching{socket?.close()};socket=null;input=null;output=null;auth=null;onState(if(was)BandConnectionState.Disconnected else BandConnectionState.Error)}
    }

    private fun parseLoop(){
        while(true){val d=buffer.toByteArray();if(d.isEmpty())return
            val magic=if(v2)byteArrayOf(0xA5.toByte(),0xA5.toByte()) else byteArrayOf(0xBA.toByte(),0xDC.toByte(),0xFE.toByte())
            val o=findMagic(d,magic);if(o<0){buffer.reset();val keep=magic.size-1;if(d.size>=keep)buffer.write(d,d.size-keep,keep)else buffer.write(d);return}
            if(o>0){consume(o);continue}
            if(!(if(v2)parseV2(d)else parseV1(d)))return
        }
    }
    private fun parseV1(d:ByteArray):Boolean{
        if(d.size<11)return false;val hl=u16(d,5);if(hl<3){consume(1);return true};val pl=hl-3;val total=11+pl;if(d.size<total)return false
        if(d[total-1]!=0xEF.toByte()){consume(1);return true};val ch=d[3].toInt()and 15;val op=d[7].toInt()and 255;val type=d[9].toInt()and 255;val payload=d.copyOfRange(10,10+pl);consume(total)
        onEvent("Xiaomi SPPv1 packet: channel=$ch opcode=$op type=$type payload=${payload.size}")
        if(ch==0&&op==1&&type==0&&payload.size==3){val ver=payload.joinToString(""){ "%02X".format(it.toInt()and 255)};onEvent("Xiaomi SPP: protocol version=$ver");if(!payload.contentEquals(byteArrayOf(2,1,9))){onState(BandConnectionState.Error);running=false;return true};v2=true;sendSessionConfig()}
        return true
    }
    private fun sendSessionConfig(){val p=byteArrayOf(1,1,3,0,1,0,0,2,2,0,0,0xFC.toByte(),3,2,0,0x20,0,4,2,0,0x10,0x27);send(encodeV2(SESSION,0,p));onEvent("Xiaomi SPP: sent SPPv2 session config")}
    private fun parseV2(d:ByteArray):Boolean{
        if(d.size<8)return false;val type=d[2].toInt()and 15;val seq=d[3].toInt()and 255;val len=u16(d,4);val total=8+len;if(d.size<total)return false
        val p=d.copyOfRange(8,total);if(u16(d,6)!=crc16(p)){onEvent("Xiaomi SPPv2 checksum mismatch seq=$seq");consume(1);return true};consume(total)
        when(type){ACK->onEvent("Xiaomi SPPv2 ACK sequence=$seq");SESSION->{val op=p.firstOrNull()?.toInt()?.and(255)?:-1;onEvent("Xiaomi SPPv2 session response opcode=$op");if(op==2)startAuth()};DATA->parseData(seq,p)};return true
    }
    private fun parseData(seq:Int,p:ByteArray){
        if(p.size<2){sendAck(seq);return};val ch=p[0].toInt()and 15;val op=p[1].toInt()and 255;val encoded=p.copyOfRange(2,p.size)
        val plain=if(op==2)auth?.decryptV2(encoded)?:run{onEvent("Xiaomi SPPv2 encrypted packet before auth");sendAck(seq);return}else encoded
        onEvent("Xiaomi SPPv2 data: channel=$ch opcode=$op bytes=${plain.size}")
        if(ch==1){if(authenticated)handleSystemCommand(plain)else auth?.handle(plain)};sendAck(seq)
    }
    private fun startAuth(){if(auth!=null)return;onState(BandConnectionState.Authenticating);auth=Auth(authKey.copyOf(),{onEvent("Xiaomi auth: $it")},{sendData(it,false)}){ok->authenticated=ok;if(ok){onState(BandConnectionState.Authenticated);onEvent("Xiaomi auth: authenticated");onEvent("Xiaomi auth: initialized");initializeSystem()}else onState(BandConnectionState.Error)};auth?.start()}
    private fun initializeSystem(){sendSystemRequest(2);sendSystemRequest(1);sendSystemRequest(29);onEvent("Xiaomi init: requested device info, battery and display items")}
    private fun sendSystemRequest(subtype:Int){val cmd=Proto.varintField(1,SYSTEM_TYPE)+Proto.varintField(2,subtype);sendData(cmd,true);onEvent("Xiaomi init: sent system command subtype=$subtype")}
    private fun handleSystemCommand(data:ByteArray){
        val cmd=Proto.parseCommand(data)?:run{onEvent("Xiaomi init: protobuf parse failed bytes=${data.size}");return};if(cmd.type!=SYSTEM_TYPE){onEvent("Xiaomi init: received command type=${cmd.type}");return}
        when(cmd.subtype){
            1->{val power=cmd.system?.let{Proto.findFieldBytes(it,2)};val battery=power?.let(Proto::parseBattery);if(battery!=null){onEvent("Xiaomi init: battery=${battery.level}%");onDataUpdate(BandDataUpdate(batteryPercentage=battery.level))}}
            2->{val info=cmd.system?.let(Proto::parseDeviceInfo);if(info!=null){onEvent("Xiaomi init: device information received");onDataUpdate(BandDataUpdate(firmware=info.firmware,model=info.model))}}
            29->{val items=cmd.system?.let(Proto::parseDisplayItems);if(items!=null){onEvent("Xiaomi init: display items=${items.size}");onDataUpdate(BandDataUpdate(displays=items))}}
            else->onEvent("Xiaomi init: received unhandled system subtype=${cmd.subtype}")
        }
    }
    private fun sendAck(seq:Int){send(encodeV2(ACK,seq,ByteArray(0)));onEvent("Xiaomi SPPv2: sent ACK sequence=$seq")}
    private fun sendData(payload:ByteArray,encrypted:Boolean){val body=if(encrypted)auth?.encryptV2(payload)?:throw IllegalStateException("Xiaomi keys not ready")else payload;val raw=byteArrayOf(1,if(encrypted)2 else 1)+body;val seq=txSeq.getAndIncrement()and 255;send(encodeV2(DATA,seq,raw));onEvent("Xiaomi SPPv2: sent data sequence=$seq opcode=${if(encrypted)2 else 1} bytes=${payload.size}")}
    private fun send(b:ByteArray){synchronized(lock){val o=output?:throw IllegalStateException("RFCOMM output stream not ready");o.write(b);o.flush()}}
    private fun encodeV2(t:Int,s:Int,p:ByteArray)=ByteArray(8+p.size).also{it[0]=0xA5.toByte();it[1]=0xA5.toByte();it[2]=(t and 15).toByte();it[3]=(s and 255).toByte();putU16(it,4,p.size);putU16(it,6,crc16(p));p.copyInto(it,8)}
    private fun consume(n:Int){val c=buffer.toByteArray();buffer.reset();if(n<c.size)buffer.write(c,n,c.size-n)}
    private fun findMagic(d:ByteArray,m:ByteArray):Int{if(d.size<m.size)return -1;for(i in 0..(d.size-m.size))if(d.copyOfRange(i,i+m.size).contentEquals(m))return i;return -1}
    private fun u16(d:ByteArray,o:Int)=(d[o].toInt()and 255)or((d[o+1].toInt()and 255)shl 8)
    private fun putU16(d:ByteArray,o:Int,v:Int){d[o]=v.toByte();d[o+1]=(v ushr 8).toByte()}
    private fun crc16(p:ByteArray):Int{var c=0;for(b in p)for(bit in 0 until 8){c=c shl 1;if((((c ushr 16)and 1)xor((b.toInt()ushr bit)and 1))==1)c=c xor 0x8005};return Integer.reverse(c)ushr 16}
}

private class Auth(
    private val secretKey:ByteArray,
    private val onEvent:(String)->Unit,
    private val sendPlain:(ByteArray)->Unit,
    private val onResult:(Boolean)->Unit
){
    private val phoneNonce=ByteArray(16);private var stage=0;private var encKey=ByteArray(16);private var decKey=ByteArray(16);private var encNonce=ByteArray(4)
    fun start(){SecureRandom().nextBytes(phoneNonce);stage=1;sendPlain(Proto.authNonce(phoneNonce));onEvent("auth_step_1 bytes=27")}
    fun handle(data:ByteArray):Boolean{
        val p=Proto.parseAuth(data)?:run{onEvent("auth_command_unparsed bytes=${data.size}");return false};onEvent("response subtype=${p.subtype}")
        if(p.subtype==26){val w=p.watch?:return false;if(stage!=1||w.nonce.size!=16||w.hmac.size!=32)return false;val d=derive(secretKey,phoneNonce,w.nonce);System.arraycopy(d,0,decKey,0,16);System.arraycopy(d,16,encKey,0,16);System.arraycopy(d,32,ByteArray(4),0,0);System.arraycopy(d,36,encNonce,0,4);if(!hmac(decKey,w.nonce+phoneNonce).contentEquals(w.hmac)){onEvent("watch_hmac_mismatch");onResult(false);return true};stage=2;val n=hmac(encKey,phoneNonce+w.nonce);val info=Proto.authDeviceInfo(Build.VERSION.SDK_INT,Build.MODEL,Locale.getDefault().language);val encrypted=ccm(encKey,nonce(encNonce),info);sendPlain(Proto.authStep2(n,encrypted));onEvent("auth_step_2 bytes=76");return true}
        if(p.subtype==27){if(stage==2){stage=3;onResult(true)}else onResult(false);return true};return false
    }
    fun encryptV2(m:ByteArray)=ctr(Cipher.ENCRYPT_MODE,encKey,encKey,m)
    fun decryptV2(m:ByteArray)=ctr(Cipher.DECRYPT_MODE,decKey,decKey,m)
    private fun ctr(mode:Int,key:ByteArray,iv:ByteArray,m:ByteArray)=Cipher.getInstance("AES/CTR/NoPadding").let{it.init(mode,SecretKeySpec(key,"AES"),IvParameterSpec(iv));it.doFinal(m)}
    private fun derive(secret:ByteArray,phone:ByteArray,watch:ByteArray):ByteArray{val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(phone+watch,"HmacSHA256"));val hk=mac.doFinal(secret);mac.init(SecretKeySpec(hk,"HmacSHA256"));val label="miwear-auth".toByteArray();val out=ByteArray(64);var tmp=ByteArray(0);var n=1;var pos=0;while(pos<64){mac.update(tmp);mac.update(label);mac.update(n.toByte());tmp=mac.doFinal();val k=minOf(32,64-pos);tmp.copyInto(out,pos,0,k);pos+=k;n++};return out}
    private fun hmac(k:ByteArray,m:ByteArray)=Mac.getInstance("HmacSHA256").let{it.init(SecretKeySpec(k,"HmacSHA256"));it.doFinal(m)}
    private fun nonce(n:ByteArray)=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(n).putInt(0).putInt(0).array()
    private fun ccm(k:ByteArray,n:ByteArray,p:ByteArray):ByteArray{val c=CCMBlockCipher(AESEngine());c.init(true,AEADParameters(KeyParameter(k),32,n,null));val o=ByteArray(c.getOutputSize(p.size));val x=c.processBytes(p,0,p.size,o,0);c.doFinal(o,x);return o}
}

private object Proto{
    data class Cmd(val type:Int,val subtype:Int,val system:ByteArray?)
    data class AuthParsed(val subtype:Int,val watch:Watch?)
    data class Watch(val nonce:ByteArray,val hmac:ByteArray)
    data class Battery(val level:Int)
    data class Info(val firmware:String,val model:String)
    private data class Fields(val vars:MutableMap<Int,Int>=mutableMapOf(),val bytes:MutableMap<Int,ByteArray>=mutableMapOf(),val repeated:MutableMap<Int,MutableList<ByteArray>> = mutableMapOf())
    private data class Rv(val value:Int,val next:Int)
    fun varintField(f:Int,v:Int)=tag(f,0)+varint(v)
    fun bytesField(f:Int,v:ByteArray)=tag(f,2)+varint(v.size)+v
    fun authNonce(n:ByteArray)=varintField(1,1)+varintField(2,26)+bytesField(3,bytesField(30,bytesField(1,n)))
    fun authStep2(a:ByteArray,b:ByteArray)=varintField(1,1)+varintField(2,27)+bytesField(3,bytesField(32,bytesField(1,a)+bytesField(2,b)))
    fun authDeviceInfo(api:Int,model:String,lang:String):ByteArray{val fixed=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(api.toFloat()).array();return varintField(1,0)+tag(2,5)+fixed+bytesField(3,model.toByteArray())+varintField(4,224)+bytesField(5,lang.take(2).uppercase(Locale.ROOT).toByteArray())}
    fun parseAuth(d:ByteArray):AuthParsed?{val f=parse(d)?:return null;val st=f.vars[2]?:return null;val a=f.bytes[3]?:return AuthParsed(st,null);val af=parse(a)?:return AuthParsed(st,null);return AuthParsed(st,af.bytes[31]?.let{parseWatch(it)})}
    private fun parseWatch(d:ByteArray):Watch?{val f=parse(d)?:return null;return Watch(f.bytes[1]?:return null,f.bytes[2]?:return null)}
    fun parseCommand(d:ByteArray):Cmd?{val f=parse(d)?:return null;return Cmd(f.vars[1]?:return null,f.vars[2]?:0,f.bytes[4])}
    fun findFieldBytes(d:ByteArray,f:Int)=parse(d)?.bytes?.get(f)
    fun parseBattery(power:ByteArray):Battery?{val p=parse(power)?:return null;val b=p.bytes[1]?:return null;val bf=parse(b)?:return null;return Battery((bf.vars[1]?:return null).coerceIn(0,100))}
    fun parseDeviceInfo(system:ByteArray):Info?{val s=parse(system)?.bytes?.get(3)?:return null;val f=parse(s)?:return null;return Info(f.bytes[2]?.toString(Charsets.UTF_8)?:return null,f.bytes[4]?.toString(Charsets.UTF_8)?:return null)}
    fun parseDisplayItems(system:ByteArray):List<BandDisplay>?{val s=parse(system)?.bytes?.get(10)?:return null;val f=parse(s)?:return null;return f.repeated[1].orEmpty().mapNotNull{val d=parse(it)?:return@mapNotNull null;val code=d.bytes[1]?.toString(Charsets.UTF_8)?:return@mapNotNull null;BandDisplay(code,d.bytes[2]?.toString(Charsets.UTF_8).orEmpty(),(d.vars[3]?:0)!=0,d.vars[4]?:0,(d.vars[6]?:0)!=0)}}
    private fun parse(d:ByteArray):Fields?{val f=Fields();var p=0;while(p<d.size){val t=rv(d,p)?:return null;p=t.next;val no=t.value ushr 3;when(t.value and 7){0->{val v=rv(d,p)?:return null;p=v.next;f.vars[no]=v.value};2->{val l=rv(d,p)?:return null;p=l.next;if(l.value<0||p+l.value>d.size)return null;val b=d.copyOfRange(p,p+l.value);p+=l.value;f.bytes[no]=b;f.repeated.getOrPut(no){mutableListOf()}.add(b)};1->{if(p+8>d.size)return null;p+=8};5->{if(p+4>d.size)return null;p+=4};else->return null}};return f}
    private fun rv(d:ByteArray,s:Int):Rv?{var p=s;var v=0;var sh=0;while(p<d.size&&sh<32){val b=d[p++].toInt()and 255;v=v or((b and 127)shl sh);if((b and 128)==0)return Rv(v,p);sh+=7};return null}
    private fun tag(n:Int,w:Int)=varint((n shl 3)or w)
    private fun varint(i:Int):ByteArray{var v=i;val o=ArrayList<Byte>();do{var b=v and 127;v=v ushr 7;if(v!=0)b=b or 128;o.add(b.toByte())}while(v!=0);return o.toByteArray()}
}
