$VERSION="0.4.2"
$AMMONIUM_VERSION="0.8.3-1"
$SCALA_VERSION="2.11.11" # Set to 2.12.2 for Scala 2.12

$TYPELEVEL_SCALA=$false # If true, set SCALA_VERSION above to a both ammonium + TLS available version (e.g. 2.11.8)

$EXTRA_OPTS=@()

function Make-Temp
{
    $find_temp=$false
    $random=Get-Random
    $result="$env:TEMP\$random"
    while(!($find_temp)) {
        $result="$env:TEMP\$random"
        if(Test-Path $result) {
            $random=Get-Random
        } else {
            $find_temp=$true
        }
    }
    $result
}

$COURSIER_JAR=Make-Temp

echo "Getting coursier launcher..."
(New-Object Net.WebClient).DownloadFile('https://git.io/vgvpD', $COURSIER_JAR)
echo "Done"

if($TYPELEVEL_SCALA) {
    $EXTRA_OPTS += @(
        "-E", "org.scala-lang:scala-compiler",
        "-E", "org.scala-lang:scala-library",
        "-E", "org.scala-lang:scala-reflect",
        "-I", "ammonite:org.typelevel:scala-compiler:$SCALA_VERSION",
        "-I", "ammonite:org.typelevel:scala-library:$SCALA_VERSION",
        "-I", "ammonite:org.typelevel:scala-reflect:$SCALA_VERSION"
    )
}

& "java" -noverify -jar $COURSIER_JAR launch `
  -r sonatype:releases -r sonatype:snapshots `
  -i ammonite `
  -I "ammonite:org.jupyter-scala:ammonite-runtime_$SCALA_VERSION`:$AMMONIUM_VERSION" `
  -I "ammonite:org.jupyter-scala:scala-api_$SCALA_VERSION`:$VERSION" `
  $EXTRA_OPTS `
  "org.jupyter-scala:scala-cli_$SCALA_VERSION`:$VERSION" `
  -- `
    --id scala `
    --name "Scala" `
    $args

rm $COURSIER_JAR
