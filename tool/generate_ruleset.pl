#!/usr/bin/env perl

use strict;
use warnings;
use utf8;

use File::Copy;
use File::Path;

my $sniff_list_path = 'sniffs.txt';

my @sniffs;

open my $fh, "<", $sniff_list_path or die "can't read file : $sniff_list_path";

while (<$fh>) {
    chomp;
    push @sniffs, $_;
}
close $fh;

my $head = << "HEAD";
<?xml version="1.0" encoding="ISO-8859-1"?>
<rules>
HEAD

my $head2 = << "HEAD";
<?xml version="1.0" encoding="ISO-8859-1"?>
<profile>
  <name>All PHP CodeSniffer Rules</name>
    <language>php</language>
      <rules>
HEAD

my $rule = << "RULE";
<rule key="%s" priority="BLOCKER">
<category name="" />
<name>%s</name>
<configKey>rulesets/%s</configKey>
<description>![CDATA[test]]</description>
</rule>
RULE

my $rule2 = << "RULE";
    <rule>
        <repositoryKey>php_codesniffer_rules</repositoryKey>
        <key>%s</key>
    </rule>
RULE

my $foot = << "FOOT";
</rules>
FOOT

my $foot2 = << "FOOT";
  </rules>
</profile>
FOOT

open my $fh2, '>', 'rules.xml' or die "can't open file : rules.xml";
open my $fh3, '>', 'all-phpcs-profile.xml' or die "can't open file : all-phpcs-profile.xml";
print $fh2 $head;
print $fh3 $head2;

for my $sniff (@sniffs) {
    my $sniff_short = (split /\./, $sniff)[-1];
    print $fh2 sprintf($rule, $sniff, $sniff_short, $sniff_short);
    print $fh3 sprintf($rule2, $sniff);
}

print $fh2 $foot;
print $fh3 $foot2;

close $fh2;
close $fh3;

mkdir qw|../sonar-php-plugin/target/classes/org/sonar/plugins/php/profiles/|;
move 'rules.xml', '../sonar-php-plugin/src/main/resources/org/sonar/plugins/php/codesniffer/rules.xml';
move 'all-phpcs-profile.xml', '../sonar-php-plugin/target/classes/org/sonar/plugins/php/profiles/all-phpcs-profile.xml';
