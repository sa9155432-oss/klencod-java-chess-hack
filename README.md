# romy hack

مشروع Android Java أُنشئ بـ KlencodIDE.

## القالب: لوحة رسم بسيطة 🎨

### الميزات
- رسم بالإصبع (Canvas + Path)
- 8 ألوان + 3 سماكات فرشاة
- تراجع (Undo) + مسح الكل
- حفظ PNG في Pictures/KlencodDrawings (Android 10+)
- كل المنطق في ملف MainActivity.java واحد
- لا مكتبات خارجية

### البناء

يتم البناء تلقائياً على GitHub Actions عند الضغط على "بناء APK" من داخل التطبيق.

### الهيكل
- `app/src/main/java/` — كود Java (MainActivity.java فقط)
- `app/src/main/res/` — الموارد (strings.xml + app_icon.png)
- `app/src/main/AndroidManifest.xml` — بيان التطبيق
- `app/module.toml` — إعدادات الوحدة

### التوسيعات المقترحة
- 🧹 ممحاة (Eraser) — بتغيير Paint xfermode
- ↪️ إعادة (Redo) — بمكدس ثانٍ
- 📤 مشاركة الصورة — عبر Intent.ACTION_SEND
- 🎨 منتقي ألوان HSL
- 🌐 شبكة خلفية
