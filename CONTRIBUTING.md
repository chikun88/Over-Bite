# 開発手順

## 開発環境

- Android Studio、JDK 21、Android SDK Platform 35
- Gradleは同梱Wrapper（9.4.1）を使用します。AGPは9.2.1、Kotlinは2.2.10です。
- アプリID: `com.chihaya.overbite2`、対応端末: Android 6.0（API 23）以上・USB Host対応。

```sh
git clone https://github.com/chikun88/Over-Bite.git
cd Over-Bite
```

Windowsでは日本語を含まないパスへのcloneを推奨します（例: `C:/dev/Over-Bite`）。日本語パスではJUnitワーカーがテストクラスを読み込めない場合があります。

Android Studioで開き、SDKの場所を設定してGradle Syncしてください。
`local.properties` はPCごとに生成し、Gitへ登録しません。

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS / Linux:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`。
これは開発用のデバッグ署名APKです。配布用の署名鍵はリポジトリへ登録せず、別途安全に管理してください。

## 変更の進め方

1. Issueで不具合・改善内容を記録します。
2. `main`から作業ブランチを作成します（例: `git switch -c fix/usb-connection`）。
3. 変更と関連テストをコミットし、ブランチをpushします。
4. Pull Requestを作成し、Android CIの成功を確認してmainへマージします。

Actionsでは単体テスト、Android Lint、APKビルドを実行します。
成功した実行のArtifactsから `Over-Bite-debug` をダウンロードできます（GitHubへのログインが必要です）。
CIではUSB機器・実機操縦の確認は行いません。通信形式や割り当てを変更した際は、既存READMEの実機テストの注意に従って確認してください。

## 公開するファイル

ソース、Gradle Wrapper、説明書、プロジェクト画像を管理します。
ビルド出力、古いAPK、SDKパス、IDE設定、署名鍵、個人用作業データは除外します。
リリースAPKを配布する場合は、対象コミットにタグを付けてGitHub Releasesへ添付してください。

## ライセンス

現時点ではプロジェクト全体の利用許諾ライセンスを指定していません。
外部コードや素材を追加する場合は、その利用条件と出典を確認してください。