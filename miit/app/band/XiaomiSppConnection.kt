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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

class XiaomiSppConnection(
    private val device: BluetoothDevice,
    private val authKey: ByteArray,
    private val onEvent: (String) -> Unit,
    private val onState: (BandConnectionState) -> Unit
) {
    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val V1_MAGIC = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
        private val V2_MAGIC = byteArrayOf(0xA5.toByte(), 0xA5.toByte())
        private val V1_VERSION_REQUEST = byteArrayOf(0xBA.toByte(),0xDC.toByte(),0xFE.toByte(),0x00,0xC0.toByte(),0x03,0x00,0x00,0x00,0x00,0xEF.toByte())
    }
    @Volatile private var running = false
    @Volatile private var authenticated = false
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var worker: Thread? = null
    private val writeLock = Any()
    private val txSequence = AtomicInteger(0)
    private val rxBuffer = ByteArrayOutputStream()
    private var parserVersion = 1
    private var auth: XiaomiSppAuthenticator? = null

    @SuppressLint("MissingPermission") fun connect() {
        if (running) return
        if (authKey.size != 16) { onEvent("SPP auth key invalid: expected 16 bytes"); onState(BandConnectionState.Error); return }
        worker = Thread({ runConnection() }, "Miit-Xiaomi-SPP").also { it.start() }
    }
    fun close() { running=false; runCatching { socket?.close() }; worker?.interrupt(); worker=null; socket=null; input=null; output=null }

    @SuppressLint("MissingPermission") private fun runConnection() {
        running=true
        try {
            onState(BandConnectionState.Connecting)
            onEvent("Xiaomi SPP: opening RFCOMM socket uuid=$SPP_UUID")
            val s=device.createRfcommSocketToServiceRecord(SPP_UUID); socket=s; s.connect()
            input=s.inputStream; output=s.outputStream
            onEvent("Xiaomi SPP: RFCOMM connected"); onState(BandConnectionState.Connected)
            sendRaw(V1_VERSION_REQUEST); onEvent("Xiaomi SPP: sent V1 protocol-version request")
            val buf=ByteArray(4096)
            while(running){ val n=input?.read(buf) ?: -1; if(n<0) break; if(n>0){rxBuffer.write(buf,0,n); parseAvailable()} }
        } catch(t:Throwable){ if(running) onEvent("Xiaomi SPP connection error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}") }
        finally { running=false; runCatching{socket?.close()}; socket=null; input=null; output=null; if(authenticated) onState(BandConnectionState.Disconnected) else onState(BandConnectionState.Error) }
    }
    private fun parseAvailable(){
        while(true){
            val data=rxBuffer.toByteArray(); if(data.isEmpty()) return
            val magic=if(parserVersion==1)V1_MAGIC else V2_MAGIC; val offset=indexOfMagic(data,magic)
            if(offset<0){rxBuffer.reset(); if(data.size>=magic.size-1) rxBuffer.write(data.copyOfRange(data.size-(magic.size-1),data.size)); return}
            if(offset>0){consume(offset); continue}
            val progressed=if(parserVersion==1)parseV1(data) else parseV2(data); if(!progressed) return
        }
    }
    private fun parseV1(data:ByteArray):Boolean{
        if(data.size<11) return false; val len=u16le(data,5); if(len<3) return false; val total=8+len
        if(data.size<total) return false; if(data[total-1]!=0xEF.toByte()){consume(1);return true}
        val opcode=data[7].toInt() and 255; val payload=data.copyOfRange(10,total-1); consume(total)
        onEvent("Xiaomi SPP V1: opcode=$opcode payload=${payload.size}")
        if(data[3].toInt() and 255==0 && opcode==1 && payload.size==3){
            val version=payload.joinToString(""){ "%02X".format(it) }; onEvent("Xiaomi SPP: protocol version=$version")
            if(version=="020109"){parserVersion=2; onEvent("Xiaomi SPP: switching to V2"); sendSessionConfig()}
        }
        return true
    }
    private fun sendSessionConfig(){
        val payload=byteArrayOf(1,1,3,0,1,0,0,2,2,0,0,0xFC.toByte(),3,2,0,0x20,0,4,2,0,0x10,0x27)
        sendRaw(encodeV2(2,0,payload)); onEvent("Xiaomi SPP: sent V2 session config")
    }
    private fun parseV2(data:ByteArray):Boolean{
        if(data.size<8) return false; val type=data[2].toInt() and 15; val seq=data[3].toInt() and 255; val len=u16le(data,4); val total=8+len; if(data.size<total)return false
        val payload=data.copyOfRange(8,total); val given=u16le(data,6); val calc=crc(payload); if(given!=calc){onEvent("Xiaomi SPP V2 CRC mismatch seq=$seq");consume(1);return true}; consume(total)
        when(type){
            1->onEvent("Xiaomi SPP V2 ACK seq=$seq")
            2->{val op=payload.firstOrNull()?.toInt()?.and(255) ?: -1; onEvent("Xiaomi SPP V2 session response opcode=$op"); if(op==2) startAuth()}
            3->{if(payload.size<2)return true; val channel=payload[0].toInt() and 15; val op=payload[1].toInt() and 255; val body=payload.copyOfRange(2,payload.size); sendAck(seq); if(channel==1){val plain=if(op==2&&authenticated)auth?.decryptV2(body)?:body else body; auth?.handleCommand(plain)}}
        }; return true
    }
    private fun startAuth(){if(auth!=null)return; onState(BandConnectionState.Authenticating); auth=XiaomiSppAuthenticator(authKey,{e->onEvent("Xiaomi auth: $e")},{ok->{authenticated=ok;onState(if(ok)BandConnectionState.Authenticated else BandConnectionState.Error)}}){p->sendData(p)};auth?.start()}
    private fun sendAck(seq:Int)=sendRaw(encodeV2(1,seq,ByteArray(0)))
    private fun sendData(payload:ByteArray){val raw=byteArrayOf(1,1)+payload; val seq=txSequence.getAndIncrement() and 255; sendRaw(encodeV2(3,seq,raw)); onEvent("Xiaomi SPP: sent data seq=$seq bytes=${payload.size}")}
    private fun encodeV2(type:Int,seq:Int,payload:ByteArray):ByteArray{val o=ByteArray(8+payload.size);o[0]=0xA5.toByte();o[1]=0xA5.toByte();o[2]=(type and 15).toByte();o[3]=(seq and 255).toByte();putU16le(o,4,payload.size);putU16le(o,6,crc(payload));payload.copyInto(o,8);return o}
    private fun sendRaw(b:ByteArray){synchronized(writeLock){output?.write(b);output?.flush()}}
    private fun consume(n:Int){val d=rxBuffer.toByteArray();rxBuffer.reset();if(n<d.size)rxBuffer.write(d,n,d.size-n)}
    private fun indexOfMagic(d:ByteArray,m:ByteArray):Int{if(d.size<m.size)return -1;for(i in 0..d.size-m.size)if(d.copyOfRange(i,i+m.size).contentEquals(m))return i;return -1}
    private fun u16le(d:ByteArray,o:Int)=(d[o].toInt() and 255) or ((d[o+1].toInt() and 255) shl 8)
    private fun putU16le(d:ByteArray,o:Int,v:Int){d[o]=v.toByte();d[o+1]=(v ushr 8).toByte()}
    private fun crc(p:ByteArray):Int{var c=0;for(b in p)for(j in 0 until 8){c=c shl 1;if((((c ushr 16) and 1) xor ((b.toInt() ushr j) and 1))==1)c=c xor 0x8005};return Integer.reverse(c) ushr 16}
}

private class XiaomiSppAuthenticator(private val secretKey:ByteArray,private val onEvent:(String)->Unit,private val onResult:(Boolean)->Unit,private val sendPlain:(ByteArray)->Unit){
    private val phoneNonce=ByteArray(16); private var stage=0; private var decKey=ByteArray(16); private var encKey=ByteArray(16); private var encNonce=ByteArray(4)
    fun start(){SecureRandom().nextBytes(phoneNonce);stage=1;val c=Proto.commandNonce(phoneNonce);onEvent("auth step 1 bytes=${c.size}");sendPlain(c)}
    fun handleCommand(data:ByteArray):Boolean{val p=Proto.parse(data)?:return false;if(p.type!=1)return false;onEvent("response subtype=${p.subtype}");when(p.subtype){26->{val w=p.watch?:return false;if(stage!=1)return false;val d=derive(secretKey,phoneNonce,w.nonce);decKey=d.copyOfRange(0,16);encKey=d.copyOfRange(16,32);encNonce=d.copyOfRange(36,40);if(!hmac(decKey,w.nonce+phoneNonce).contentEquals(w.hmac)){onEvent("watch_hmac_mismatch");onResult(false);return true};stage=2;val ei=ccm(encKey,nonce(encNonce,0),Proto.authInfo(Build.VERSION.SDK_INT,Build.MODEL,java.util.Locale.getDefault().getLanguage()));val c=Proto.commandAuth(hmac(encKey,phoneNonce+w.nonce),ei);onEvent("auth step 2 bytes=${c.size}");sendPlain(c);return true}27->{val ok=p.status==1||p.authStatus==1;onEvent(if(ok)"authenticated":"auth_failed status=${p.status} authStatus=${p.authStatus}");if(stage==2&&ok){stage=3;onResult(true)}else onResult(false);return true}};return false}
    fun decryptV2(b:ByteArray):ByteArray=try{val c=javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");c.init(javax.crypto.Cipher.DECRYPT_MODE,SecretKeySpec(decKey,"AES"),javax.crypto.spec.IvParameterSpec(decKey));c.doFinal(b)}catch(_:Throwable){b}
    private fun derive(s:ByteArray,p:ByteArray,w:ByteArray):ByteArray{val initial=hmac(p+w,s);val m=Mac.getInstance("HmacSHA256");m.init(SecretKeySpec(initial,"HmacSHA256"));val label="miwear-auth".toByteArray();val out=ByteArray(64);var prev=ByteArray(0);var ctr=1;var off=0;while(off<64){m.update(prev);m.update(label);m.update(ctr.toByte());prev=m.doFinal();val n=minOf(32,64-off);prev.copyInto(out,off,0,n);off+=n;ctr++};return out}
    private fun hmac(k:ByteArray,v:ByteArray):ByteArray{val m=Mac.getInstance("HmacSHA256");m.init(SecretKeySpec(k,"HmacSHA256"));return m.doFinal(v)}
    private fun nonce(n:ByteArray,s:Int)=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(n).putInt(0).putInt(s).array()
    private fun ccm(k:ByteArray,n:ByteArray,p:ByteArray):ByteArray{val c=CCMBlockCipher(AESEngine());c.init(true,AEADParameters(KeyParameter(k),32,n,null));val o=ByteArray(c.getOutputSize(p.size));val z=c.processBytes(p,0,p.size,o,0);c.doFinal(o,z);return o}
}

private object Proto{
 data class Watch(val nonce:ByteArray,val hmac:ByteArray);data class Parsed(val type:Int,val subtype:Int,val status:Int,val authStatus:Int,val watch:Watch?)
 fun commandNonce(n:ByteArray)=cmd(26,bytes(3,bytes(30,bytes(1,n))))
 fun commandAuth(en:ByteArray,ei:ByteArray)=cmd(27,bytes(3,bytes(32,bytes(1,en)+bytes(2,ei))))
 fun authInfo(api:Int,model:String,lang:String):ByteArray{val f=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(api.toFloat()).array();return varint(1,0)+fixed(2,f)+str(3,model)+varint(4,224)+str(5,lang.take(2).uppercase())}
 fun parse(d:ByteArray):Parsed?{var p=0;var t=0;var st=0;var s=0;var w:Watch?=null;while(p<d.size){val a=rv(d,p)?:return null;p=a.n;val f=a.v ushr 3;val wire=a.v and 7;when(f){1,2->{if(wire!=0)return null;val x=rv(d,p)?:return null;p=x.n;if(f==1)t=x.v else st=x.v};3->{if(wire!=2)return null;val b=rb(d,p)?:return null;p=b.n;val x=auth(b.b);s=x.first;w=x.second};100->{if(wire!=0)return null;val x=rv(d,p)?:return null;p=x.n;st=x.v};else->p=skip(d,p,wire)?:return null}};return Parsed(t,st,s,w==null?null:s,w)}
 private fun auth(d:ByteArray):Pair<Int,Watch?>{var p=0;var status=0;var w:Watch?=null;while(p<d.size){val a=rv(d,p)?:break;p=a.n;val f=a.v ushr 3;val wire=a.v and 7;when{f==1&&wire==0->{val x=rv(d,p)?:break;p=x.n;status=x.v};f==31&&wire==2->{val b=rb(d,p)?:break;p=b.n;w=watch(b.b)};f==37&&wire==2->{val b=rb(d,p)?:break;p=b.n;val x=rv(b.b,0);if(x!=null&&x.v ushr 3==1)status=rv(b.b,x.n)?.v ?:status};else->{p=skip(d,p,wire)?:break}}};return status to w}
 private fun watch(d:ByteArray):Watch{var p=0;var n=ByteArray(0);var h=ByteArray(0);while(p<d.size){val a=rv(d,p)?:break;p=a.n;val f=a.v ushr 3;val wire=a.v and 7;if(wire!=2){p=skip(d,p,wire)?:break;continue};val b=rb(d,p)?:break;p=b.n;if(f==1)n=b.b else if(f==2)h=b.b};return Watch(n,h)}
 private fun cmd(st:Int,a:ByteArray)=varint(1,1)+varint(2,st)+a
 private fun bytes(n:Int,b:ByteArray)=varintTag(n,2)+varintValue(b.size)+b
 private fun str(n:Int,s:String)=bytes(n,s.toByteArray())
 private fun fixed(n:Int,b:ByteArray)=varintTag(n,5)+b
 private fun varint(n:Int,v:Int)=varintTag(n,0)+varintValue(v)
 private fun varintTag(n:Int,w:Int)=varintValue((n shl 3) or w)
 private fun varintValue(v0:Int):ByteArray{var v=v0;val o=ArrayList<Byte>();do{var b=v and 127;v=v ushr 7;if(v!=0)b=b or 128;o.add(b.toByte())}while(v!=0);return o.toByteArray()}
 private data class R(val v:Int,val n:Int);private data class B(val b:ByteArray,val n:Int)
 private fun rv(d:ByteArray,s:Int):R?{var p=s;var v=0;var sh=0;while(p<d.size&&sh<32){val b=d[p++].toInt() and 255;v=v or((b and 127) shl sh);if((b and 128)==0)return R(v,p);sh+=7};return null}
 private fun rb(d:ByteArray,s:Int):B?{val l=rv(d,s)?:return null;if(l.n+l.v>d.size)return null;return B(d.copyOfRange(l.n,l.n+l.v),l.n+l.v)}
 private fun skip(d:ByteArray,s:Int,w:Int):Int?=when(w){0->rv(d,s)?.n;1->if(s+8<=d.size)s+8 else null;2->rb(d,s)?.n;5->if(s+4<=d.size)s+4 else null;else->null}
}
