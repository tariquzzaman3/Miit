package com.miit.app

data class MiCreateSourceOption(
    val name: String,
    val idFprj: String,
    val idGmf: String = "",
    val description: String = ""
)

data class MiCreateWidgetSpec(
    val name: String,
    val widgetType: Int,
    val properties: List<String>
)

object MiCreateCatalog {
    val band9Sources = listOf(
        MiCreateSourceOption("None", "0"),
        MiCreateSourceOption("Hour", "0811", description = "Hour in 24-hour format"),
        MiCreateSourceOption("Hour Low", "0911"),
        MiCreateSourceOption("Hour High", "1000911", "0A11"),
        MiCreateSourceOption("Minute", "1011"),
        MiCreateSourceOption("Minute Low", "1111"),
        MiCreateSourceOption("Minute High", "1211"),
        MiCreateSourceOption("Second", "1811"),
        MiCreateSourceOption("Second Low", "1911"),
        MiCreateSourceOption("Second High", "1001911", "1A11"),
        MiCreateSourceOption("Day", "1812"),
        MiCreateSourceOption("Day Low", "1912"),
        MiCreateSourceOption("Day High", "1001912", "1A12"),
        MiCreateSourceOption("Week", "2012"),
        MiCreateSourceOption("Month", "1012"),
        MiCreateSourceOption("Year", "0812"),
        MiCreateSourceOption("AM/PM", "0813"),
        MiCreateSourceOption("Weather type (icon)", "3031"),
        MiCreateSourceOption("Weather temp (C)", "2031"),
        MiCreateSourceOption("Weather temp (F)", "40009031"),
        MiCreateSourceOption("Battery percent", "0841"),
        MiCreateSourceOption("Sleep status", "1841"),
        MiCreateSourceOption("BT connection status", "2041"),
        MiCreateSourceOption("Screen lock status", "3041"),
        MiCreateSourceOption("Heart rate", "0822"),
        MiCreateSourceOption("Interval HRM", "1022"),
        MiCreateSourceOption("Current step count", "0821"),
        MiCreateSourceOption("Current step (percent)", "1021"),
        MiCreateSourceOption("Current step (kilometer)", "1821"),
        MiCreateSourceOption("Active Calorie", "0823"),
        MiCreateSourceOption("Active Calorie (percent)", "1023"),
        MiCreateSourceOption("Stand Up value", "0824"),
        MiCreateSourceOption("Psychological stress", "0826"),
        MiCreateSourceOption("Weather something", "5031"),
        MiCreateSourceOption("Sleep score", "0828")
    )

    val band10Sources: List<MiCreateSourceOption> = band9Sources

    val widgetSpecs = listOf(
        MiCreateWidgetSpec("Analog Display", 27, listOf(
            "Background image", "Hour hand image", "Minute hand image", "Second hand image",
            "Hour anchor X/Y", "Minute anchor X/Y", "Second anchor X/Y", "Smooth motion"
        )),
        MiCreateWidgetSpec("Arc Progress", 42, listOf(
            "Position X/Y", "Width/Height", "Radius", "Line width",
            "Start angle", "End angle", "Range min", "Range max", "Value source"
        )),
        MiCreateWidgetSpec("Image", 30, listOf(
            "Bitmap", "X", "Y", "Width", "Height", "Alpha", "Visibility source"
        )),
        MiCreateWidgetSpec("Image List", 31, listOf(
            "Bitmap list", "X", "Y", "Width", "Height", "Alpha",
            "Alignment", "Default index", "Value source", "Spacing"
        )),
        MiCreateWidgetSpec("Digital Number", 32, listOf(
            "Bitmap list", "Digits", "Spacing", "Blanking", "Alignment", "Value source"
        )),
        MiCreateWidgetSpec("Container", 34, listOf(
            "X", "Y", "Width", "Height", "Alpha", "Visibility source"
        ))
    )
}
