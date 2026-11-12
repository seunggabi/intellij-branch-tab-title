#!/bin/bash
# Usage: ./build.sh [install] — compile against the installed IntelliJ, write dist/branch-title-<version>.jar; `install` copies it into the IDE plugins dir (restart to apply); `release` tags v<version> and publishes a GitHub release
set -euo pipefail
cd "$(dirname "$0")"
IDE="${IDE:-$HOME/Applications/IntelliJ IDEA.app/Contents}"
[ -d "$IDE" ] || IDE="/Applications/IntelliJ IDEA.app/Contents"
JAVAC="${JAVAC:-$(/usr/libexec/java_home -v 21+)/bin/javac}"
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' resources/META-INF/plugin.xml)
rm -rf out && mkdir -p out/classes dist
"$JAVAC" --release 21 -nowarn -encoding UTF-8 -cp "$IDE/lib/*" -d out/classes src/seunggabi/*.java
cp -R resources/. out/classes/
JAR="dist/branch-title-${VERSION}.jar"
(cd out/classes && jar cf "../../$JAR" .)
echo "built: $JAR"
if [ "${1:-}" = install ]; then
  IDE_VER=$(python3 -c "import json,sys;v=json.load(open(sys.argv[1]))['version'].split('.');print(v[0]+'.'+v[1])" "$IDE/Resources/product-info.json")
  DEST="$HOME/Library/Application Support/JetBrains/IntelliJIdea${IDE_VER}/plugins/branch-title/lib"
  mkdir -p "$DEST" && cp "$JAR" "$DEST/branch-title.jar"
  echo "installed: $DEST — restart the IDE"
fi
if [ "${1:-}" = release ]; then
  git diff --quiet && git diff --cached --quiet || { echo "commit first"; exit 1; }
  git tag "v$VERSION" && git push origin HEAD "v$VERSION"
  gh release create "v$VERSION" "$JAR" --title "v$VERSION" --latest \
    --notes "$(sed -n 's:.*<change-notes><!\[CDATA\[\(.*\)\]\]></change-notes>.*:\1:p' resources/META-INF/plugin.xml | sed 's:<br>:\n:g' | head -1)"
fi
