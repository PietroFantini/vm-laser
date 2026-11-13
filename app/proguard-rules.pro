# I metodi chiamati dalla pagina via WebView devono mantenere il nome.
-keepattributes JavascriptInterface
-keepclassmembers class com.vm.laser.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
# Offuscamento più aggressivo: nomi di pacchetto appiattiti.
-repackageclasses ''
-allowaccessmodification
