# تقرير تحديث التطبيق — 1.13.3

**Version code:** 82  
**Version name:** 1.13.3 (نسخة debug تعرض اللاحقة `-1`)  
**معرّف التطبيق:** `eu.kanade.tachiyomi.sy.debug`

## ما تغيّر

- أصبح Google Translate المحرك الأول، مع ثلاث محاولات، تأخير تصاعدي، فاصل زمني بين الطلبات وحد أقصى لطلبين متزامنين.
- أضيف ML Kit كخيار تلقائي لكل فقاعة لم ينجح Google في ترجمتها. تُنزّل نماذج اللغات عند أول اتصال بالإنترنت، ثم يمكن استخدامها دون اتصال.
- أضيفت رسائل Toast تذكر عدد الفقاعات التي تعذّر التعامل معها وتوضح ما إذا كان السبب اكتشاف المناطق/قراءة OCR أو فشل محركي الترجمة.
- رُفع رقم الإصدار، وأُضيفت تبعية ML Kit، وأُبطلت نتائج الصفحات المخبأة القديمة كي لا تحجب السلوك الجديد.

## الملفات الجديدة أو المعدّلة

- **جديد:** `app/src/main/java/eu/kanade/tachiyomi/ui/reader/translation/MLKitTranslate.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/translation/GoogleTranslate.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/translation/ReaderTranslator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/translation/TranslationPreferences.kt`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `i18n-sy/src/commonMain/moko-resources/base/strings.xml`

## التحقق والبناء

- اكتمل `:app:assembleDebug` محلياً بنجاح باستخدام Android API 37؛ واجتاز APK فحوص سلامة الأرشيف.
- أُعدّ المستودع لتشغيل سير عمل `Build debug APK` عبر GitHub Actions (`workflow_dispatch`).

## التوقيع والتحديث دون فقد البيانات

الـ APK الحالي من المستخدم والـ APK المحلي الجديد لهما معرّف التطبيق نفسه ورقم إصدار أحدث، لكن شهادة التوقيع مختلفة. يلزم المفتاح الخاص الأصلي `debug.keystore`، أو مخزن مفاتيح مكافئ، لتوقيع النسخة الجديدة بالشهادة القديمة وقبولها كتحديث فوق النسخة المثبتة. لا يمكن استعادة المفتاح الخاص من ملف APK. سير عمل debug الافتراضي على GitHub ينشئ/يستخدم شهادة debug على عامل البناء، لذلك لن يطابق الشهادة القديمة ما لم يُضف المفتاح الأصلي بأمان إلى إعدادات البناء.
