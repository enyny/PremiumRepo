name: Build

concurrency:
  group: "build"
  cancel-in-progress: true

on:
  push:
    branches:
      - master
      - main
    paths-ignore:
      - '*.md'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source
        uses: actions/checkout@v4
        with:
          path: "src"

      - name: Checkout Builds Branch
        uses: actions/checkout@v4
        with:
          ref: "builds"
          path: "builds"

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: 17
          distribution: 'temurin'
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Setup Properties
        run: |
          cd $GITHUB_WORKSPACE/src
          touch local.properties
          # Jika nanti butuh secret spesifik, tambahkan di bawah ini:
          # echo "NAMA_KEY=${{ secrets.NAMA_SECRET_DI_GITHUB }}" >> local.properties

      - name: Build Plugins
        run: |
          cd $GITHUB_WORKSPACE/src
          chmod +x gradlew
          ./gradlew make makePluginsJson ensureJarCompatibility

      - name: Move Artifacts to Builds Folder
        run: |
          # Hapus file lama di folder builds
          rm -f $GITHUB_WORKSPACE/builds/*.cs3
          rm -f $GITHUB_WORKSPACE/builds/*.jar
          rm -f $GITHUB_WORKSPACE/builds/plugins.json

          # Copy file baru dari src ke folder builds
          # Menggunakan '|| :' agar tidak error jika tidak ada file .jar (hanya .cs3)
          cp src/**/build/*.cs3 $GITHUB_WORKSPACE/builds/ 2>/dev/null || :
          cp src/**/build/*.jar $GITHUB_WORKSPACE/builds/ 2>/dev/null || :
          cp src/build/plugins.json $GITHUB_WORKSPACE/builds/ 2>/dev/null || :

      - name: Push builds
        run: |
          cd $GITHUB_WORKSPACE/builds
          git config --local user.email "actions@github.com"
          git config --local user.name "GitHub Actions"
          git add .
          # Commit amend untuk menimpa commit terakhir (agar history tidak menumpuk)
          git commit --amend -m "Build $GITHUB_SHA" || git commit -m "Build $GITHUB_SHA"
          git push --force
