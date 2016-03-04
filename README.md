Hack Sonar PHP plugin
=========================

プラグインインストール方法
--------------------------------

```
git clone --recursive https://github.com/thebrews/sonar-php
cd sonar-php
git checkout hack
cd tool/
phpcs -e | grep "^  " | sed -e 's/ //g' > sniffs.txt
perl ./generate_ruleset.pl
cd ../
mvn compile -Dlicense.skip=true &&  mvn package -Dlicense.skip=true
mkdir -p $SONAR_HOME/extensions/downloads/
cp ./sonar-php-plugin/target/sonar-php-plugin-2.7.jar $SONAR_HOME/extensions/downloads/
$SONAR_HOME/bin/macosx-universal-64/sonar.sh restart # macの場合
```

使い方
-------

- 事前にプロファイル`All PHP CodeSniffer Rules`をdefaultにしておく
- 入力ファイルはcheckstyle形式で出力される必要がある

```
php phpcs --report=checkstyle --report-file=phpcs.xml <検査対象>
$SONAR_SCANNER_HOME/bin/sonar-scanner # 実行時ディレクトリに phpcs.xml が存在していること！
```

公式プラグインとの違い
-----------------------

本プラグインは、[SoanrCommunityから提供されているSonarQube PHPプラグイン](https://github.com/SonarCommunity/sonar-php)をハックしたものです。

[PHP_CodeSniffer](https://github.com/squizlabs/PHP_CodeSniffer)(以下、phpcs)の検査結果を読み込み、その結果をSonarQubeに登録する処理を追加しています。

phpcs結果を読み込むにあたり検討したこと
---------------------------------------------

phpcsの指摘情報には"source"という属性が含まれます。これは指摘箇所がどういったルールに違反したのかを分別するためのキーのようなものになります。
(source値の例 : `Squiz.WhiteSpace.ControlStructureSpacing.SpacingAfterClose`)

"source"に加え、対象ファイルの何行目、何列目、指摘説明などをSonarQubeのDBに登録することで、SonarQubeのWeb画面で指摘件数や内容を確認することができます。

SonarQube側は、(phpcsに限らずどのような検査処理を行ったとしても)予めどのような"source"が送られてくるかを全て登録しておく必要があります。登録されていない"source"を含む指摘情報が送られてきた場合、この指摘情報はDBに登録されません（もしくは登録されているが、Web画面に表示されないのかもしれない。この辺は未調査）。

したがって、phpcsが指定する"source"の値を全て把握し、これらをSonarQubeに登録しなければなりません。

しかし、phpcsでは"source"の値の一部をプログラム内で動的に作り上げています。作り上げるといっても、条件に応じて"source"の末尾を".hoge"とするか".foo"とするかというようなものなので、プログラムを細かく追えば、"source"がどのような値を取りうるかを調査することが可能です。ただし該当するソース数も少なくなく、全てを追うには結構な工数がかかります。

（全てを調査したわけではないですが、）"source"の値は`[Snifferクラス名].[動的に作成される文字列]`から構成されることがわかりました。

そこで、SonarQubeに送る"source"の値は、動的に作成される文字列を除外し、`[Snifferクラス名]`のみとします。  
この方針とすることで、予め"source"の取りうる値を全て把握しSonarQubeに登録することが可能となります。  
これを実現するため、sonar-phpプラグインに対して

- phpcsの検査結果ファイルを読み込む
- phpcsで動的に付与された文字列を除外するようsourceの値を書き換える

という処理を行なうよう、コードを修正しています。

