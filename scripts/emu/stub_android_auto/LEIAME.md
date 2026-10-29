# Stub do Android Auto para o emulador

Fora do app REau e do build do Gradle. Serve só aos testes instrumentais no AVD `reau_test_api34_gapps`.

Por que existe: a imagem `google_apis` já traz um stub do Android Auto (`com.google.android.projection.gearhead`,
`1.2.531830-stub`, em `/product/priv-app`), sem `DefaultSettingsActivity` e sem receiver, mas os testes
(`MainActivityTest`) esperam exatamente `1.2.551000-stub`. Este APK não tem código nem telas: só o pacote e a versão.
Os apps de voz (WhatsApp, Telegram, Signal) não precisam de stub: os testes só comparam os nomes dos pacotes.

Construir (só aapt2, zipalign e apksigner das build-tools; assina com `~/.android/debug.keystore`):

    scripts/emu/stub_android_auto/construir.sh        # gera build/gearhead-stub.apk

Instalar (só em emulator-5580; é app de sistema da imagem, então o arquivo em /product é trocado por um overlayfs
gravável e o emulador reinicia):

    REAU_AVD=reau_test_api34_gapps scripts/emu/subir_emulador.sh --frio     # *_gapps sobe com -writable-system
    scripts/emu/instalar_stubs.sh

Todo boot do AVD precisa de `-writable-system` (o `subir_emulador.sh` faz isso nos AVDs `*_gapps`); sem a flag o
overlay não é montado e volta o stub da imagem. `instalar_stubs.sh` é idempotente.
