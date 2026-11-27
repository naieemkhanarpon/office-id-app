# Office ID — Android অ্যাপ (APK বানানোর নিয়ম)

এই প্রজেক্টটা আপনার PHP সাইটকে (`idcard/index.php`) Android অ্যাপের ভেতরে চালায়।
অ্যাপে যা বাড়তি পাওয়া যায়:
- যেকোনো NFC কার্ডের UID ও ভেতরের লিংক পড়া, এবং কার্ডে লিংক লেখা (Chrome ছাড়াই)
- ক্যামেরা QR স্ক্যান, ছবি আপলোড, CSV ডাউনলোড, কার্ড প্রিন্ট
- একবার লগইন করলে অনেকদিন লগইন থাকে

## পদ্ধতি ক: GitHub দিয়ে (কিছু ইনস্টল লাগে না — সুপারিশ)
1. github.com এ একটা নতুন repository খুলুন (যেমন `office-id-app`), Private রাখলেও চলবে।
2. এই ফোল্ডারের সব ফাইল আপলোড করুন (Add file → Upload files)।
3. `.github` ফোল্ডার আপলোডে না এলে: Add file → Create new file → নাম লিখুন
   `.github/workflows/build.yml` → `github-workflow-build.yml` ফাইলের লেখা পেস্ট করুন → Commit।
4. Actions ট্যাবে "Build APK" চলবে (৩-৬ মিনিট)। সবুজ টিক এলে ওই রানে ঢুকে নিচে
   Artifacts → `office-id-apk` ডাউনলোড করুন, ভেতরে `app-debug.apk`।
5. APK ফোনে নিয়ে ইনস্টল করুন (Unknown sources / "এই সোর্স থেকে ইনস্টল" অনুমতি দিতে হবে)।

## পদ্ধতি খ: Android Studio দিয়ে
1. Android Studio (Koala বা নতুন) ইনস্টল করুন → Open → এই ফোল্ডার বেছে নিন।
2. Sync শেষ হলে Build → Build APK(s)। ফাইল: `app/build/outputs/apk/debug/app-debug.apk`

## প্রথমবার অ্যাপ খুললে
- "Server URL" চাইবে: `https://আপনার-ডোমেইন/idcard/` লিখুন (HTTPS লাগবে)।
- পরে বদলাতে: অ্যাডমিন → সেটিংস → "📱 অ্যাপের সার্ভার বদলান"।
- ক্যামেরার অনুমতি চাইলে Allow দিন। ফোনের সেটিংসে NFC চালু রাখুন।

## নোট
- এটা debug APK (নিজের ব্যবহারের জন্য ঠিক আছে)। Play Store-এ দিতে চাইলে release signing লাগবে।
- সাইটের `index.php` নতুন ভার্সনটাই আপলোড করুন, নইলে অ্যাপের NFC বাটন কাজ করবে না।
