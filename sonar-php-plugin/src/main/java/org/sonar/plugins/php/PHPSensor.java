/*
 * SonarQube PHP Plugin
 * Copyright (C) 2010 SonarSource and Akram Ben Aissi
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.php;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.sonar.sslr.api.RecognitionException;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.component.Perspective;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.NoSonarFilter;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.measures.PersistenceMode;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.Rule;
import org.sonar.api.rules.RuleFinder;
import org.sonar.api.rules.RuleQuery;
import org.sonar.api.rules.Violation;
import org.sonar.api.source.Highlightable;
import org.sonar.api.source.Highlightable.HighlightingBuilder;
import org.sonar.api.source.Symbolizable;
import org.sonar.api.source.Symbolizable.SymbolTableBuilder;
import org.sonar.php.PHPAnalyzer;
import org.sonar.php.checks.CheckList;
import org.sonar.php.highlighter.SymbolHighlightingData;
import org.sonar.php.highlighter.SyntaxHighlightingData;
import org.sonar.php.metrics.FileMeasures;
import org.sonar.plugins.php.api.Php;
import org.sonar.plugins.php.api.symbols.Symbol;
import org.sonar.plugins.php.api.visitors.Issue;
import org.sonar.plugins.php.api.visitors.PHPCheck;
import org.sonar.plugins.php.api.visitors.PHPCustomRulesDefinition;
import org.sonar.plugins.php.codesniffer.PhpCodeSnifferViolation;
import org.sonar.plugins.php.codesniffer.PhpCodeSnifferViolationsXmlParser;
import org.sonar.squidbridge.ProgressReport;
import org.sonar.squidbridge.api.AnalysisException;

public class PHPSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(PHPSensor.class);

  private static final String PHPCS_REPOSITORY_KEY = "php_codesniffer_rules";

  private final ResourcePerspectives resourcePerspectives;
  private final FileSystem fileSystem;
  private final FilePredicate mainFilePredicate;
  private final FileLinesContextFactory fileLinesContextFactory;
  private final PHPChecks checks;
  private final NoSonarFilter noSonarFilter;
  private SensorContext context;
  private Php php;
  private RuleFinder ruleFinder;
  private PhpCodeSnifferViolationsXmlParser parser;
  private RulesProfile rulesProfile;

  private Collection<Rule> rulesFromRepository;

  public PHPSensor(ResourcePerspectives resourcePerspectives,
          FileSystem fileSystem,
          FileLinesContextFactory fileLinesContextFactory,
          CheckFactory checkFactory,
          NoSonarFilter noSonarFilter,
          Php php,
          RuleFinder ruleFinder,
          PhpCodeSnifferViolationsXmlParser parser,
          RulesProfile rulesProfile) {
    this(resourcePerspectives, fileSystem, fileLinesContextFactory, checkFactory, noSonarFilter, null, php, ruleFinder, parser, rulesProfile);
  }

  public PHPSensor(ResourcePerspectives resourcePerspectives,
          FileSystem fileSystem,
          FileLinesContextFactory fileLinesContextFactory,
          CheckFactory checkFactory,
          NoSonarFilter noSonarFilter,
          @Nullable PHPCustomRulesDefinition[] customRulesDefinitions,
          Php php,
          RuleFinder ruleFinder,
          PhpCodeSnifferViolationsXmlParser parser,
          RulesProfile rulesProfile) {

    this.checks = PHPChecks.createPHPCheck(checkFactory)
      .addChecks(CheckList.REPOSITORY_KEY, CheckList.getChecks())
      .addCustomChecks(customRulesDefinitions);
    this.resourcePerspectives = resourcePerspectives;
    this.fileLinesContextFactory = fileLinesContextFactory;
    this.fileSystem = fileSystem;
    this.noSonarFilter = noSonarFilter;
    this.mainFilePredicate = this.fileSystem.predicates().and(
      this.fileSystem.predicates().hasType(InputFile.Type.MAIN),
      this.fileSystem.predicates().hasLanguage(Php.KEY));
    this.php = php;
    this.ruleFinder = ruleFinder;
    this.parser = parser;
    this.rulesProfile = rulesProfile;
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    return fileSystem.hasFiles(mainFilePredicate);
  }

  @Override
  public void analyse(Project project, SensorContext context) {
    this.context = context;

    ImmutableList<PHPCheck> visitors = getCheckVisitors();

    PHPAnalyzer phpAnalyzer = new PHPAnalyzer(fileSystem.encoding(), visitors);
    ArrayList<InputFile> inputFiles = Lists.newArrayList(fileSystem.inputFiles(mainFilePredicate));

    ProgressReport progressReport = new ProgressReport("Report about progress of PHP analyzer", TimeUnit.SECONDS.toMillis(10));
    progressReport.start(Lists.newArrayList(fileSystem.files(mainFilePredicate)));

    rulesFromRepository = ruleFinder.findAll(RuleQuery.create().withRepositoryKey(PHPCS_REPOSITORY_KEY));

	// ------------------------------------------------------------
    // PHPCSを実行する
	// ------------------------------------------------------------
    File report = new File("phpcs.xml"); // TODO 本来ここはconfigureation.getReportPath()だった
    List<PhpCodeSnifferViolation> violations = parser.getViolations(report);

    LOG.info("Number of phpcs violations : " + violations.size());

    analyseFiles(phpAnalyzer, inputFiles, progressReport, violations);

  }

  @VisibleForTesting
  void analyseFiles(PHPAnalyzer phpAnalyzer, List<InputFile> inputFiles, ProgressReport progressReport, List<PhpCodeSnifferViolation> violations) {
    boolean success = false;
    try {
      for (InputFile inputFile : inputFiles) {
        progressReport.nextFile();
        analyseFile(phpAnalyzer, inputFile, violations);
      }
      success = true;
    } finally {
      stopProgressReport(progressReport, success);
    }
  }

  private static void stopProgressReport(ProgressReport progressReport, boolean success) {
    if (success) {
      progressReport.stop();
    } else {
      progressReport.cancel();
    }
  }

  private void analyseFile(PHPAnalyzer phpAnalyzer, InputFile inputFile, List<PhpCodeSnifferViolation> violations) {
    try {
      phpAnalyzer.nextFile(inputFile.file());
      phpAnalyzer.analyze();
      saveIssues(violations, inputFile);
      saveSyntaxHighlighting(phpAnalyzer.getSyntaxHighlighting(), inputFile);
      saveSymbolHighlighting(phpAnalyzer.getSymbolHighlighting(), inputFile);
      saveNewFileMeasures(phpAnalyzer.computeMeasures(fileLinesContextFactory.createFor(inputFile)), inputFile);
    } catch (RecognitionException e) {
      checkInterrupted(e);
      LOG.error("Unable to parse file: " + inputFile.absolutePath());
      LOG.error(e.getMessage());
      return;
    } catch (Exception e) {
      checkInterrupted(e);
      throw new AnalysisException("Could not analyse " + inputFile.absolutePath(), e);
    }
  }

  private static void checkInterrupted(Exception e) {
    Throwable cause = Throwables.getRootCause(e);
    if (cause instanceof InterruptedException || cause instanceof InterruptedIOException) {
      throw new AnalysisException("Analysis cancelled", e);
    }
  }

  private void saveSymbolHighlighting(List<SymbolHighlightingData> highlightingDataList, InputFile inputFile) {
    Symbolizable symbolizable = perspective(Symbolizable.class, inputFile);
    if (symbolizable != null) {
      SymbolTableBuilder symbolTableBuilder = symbolizable.newSymbolTableBuilder();

      for (SymbolHighlightingData symbolHighlightingData : highlightingDataList) {
        org.sonar.api.source.Symbol symbol = symbolTableBuilder.newSymbol(symbolHighlightingData.startOffset(), symbolHighlightingData.endOffset());

        for (Integer referenceStartOffset : symbolHighlightingData.referencesStartOffset()) {
          symbolTableBuilder.newReference(symbol, referenceStartOffset);
        }
      }

      symbolizable.setSymbolTable(symbolTableBuilder.build());
    }
  }

  private void saveSyntaxHighlighting(List<SyntaxHighlightingData> highlightingDataList, InputFile inputFile) {
    Highlightable highlightable = perspective(Highlightable.class, inputFile);
    if (highlightable != null) {
      HighlightingBuilder highlightingBuilder = highlightable.newHighlighting();

      for (SyntaxHighlightingData highlightingData : highlightingDataList) {
        highlightingBuilder.highlight(highlightingData.startOffset(), highlightingData.endOffset(), highlightingData.highlightCode());
      }

      highlightingBuilder.done();
    }

  }

  @Nullable
  <P extends Perspective<?>> P perspective(Class<P> clazz, InputFile file) {
    P result = resourcePerspectives.as(clazz, file);
    if (result == null) {
      LOG.warn("Could not get " + clazz.getCanonicalName() + " for " + file);
    }
    return result;
  }

  private void saveNewFileMeasures(FileMeasures fileMeasures, InputFile inputFile) {
    context.saveMeasure(inputFile, CoreMetrics.LINES, fileMeasures.getLinesNumber());
    context.saveMeasure(inputFile, CoreMetrics.NCLOC, fileMeasures.getLinesOfCodeNumber());
    context.saveMeasure(inputFile, CoreMetrics.COMMENT_LINES, fileMeasures.getCommentLinesNumber());
    context.saveMeasure(inputFile, CoreMetrics.CLASSES, fileMeasures.getClassNumber());
    context.saveMeasure(inputFile, CoreMetrics.FUNCTIONS, fileMeasures.getFunctionNumber());
    context.saveMeasure(inputFile, CoreMetrics.STATEMENTS, fileMeasures.getStatementNumber());

    context.saveMeasure(inputFile, CoreMetrics.COMPLEXITY, fileMeasures.getFileComplexity());
    context.saveMeasure(inputFile, CoreMetrics.COMPLEXITY_IN_CLASSES, fileMeasures.getClassComplexity());
    context.saveMeasure(inputFile, CoreMetrics.COMPLEXITY_IN_FUNCTIONS, fileMeasures.getFunctionComplexity());

    context.saveMeasure(inputFile, fileMeasures.getFunctionComplexityDistribution().build(true).setPersistenceMode(PersistenceMode.MEMORY));
    context.saveMeasure(inputFile, fileMeasures.getFileComplexityDistribution().build(true).setPersistenceMode(PersistenceMode.MEMORY));

    noSonarFilter.addComponent(context.getResource(inputFile).getEffectiveKey(), fileMeasures.getNoSonarLines());
  }

  private void saveIssues(List<PhpCodeSnifferViolation> violations, InputFile inputFile) {
    for (PhpCodeSnifferViolation v : violations) {
      Issuable issuable = resourcePerspectives.as(Issuable.class, inputFile);

      if (!inputFile.absolutePath().equals(v.getSourcePath())) {
        continue;
      }

      LOG.info("inputFile.absolutePath() : " + inputFile.absolutePath());
      LOG.info("v.getSourcePath() : " + v.getSourcePath());
      LOG.info("v.getRuleKey() : " + v.getRuleKey());
      LOG.info("v.getRuleName() : " + v.getRuleName());
      LOG.info("v.getLongMessage() : " + v.getLongMessage());
      LOG.info("v.getType() : " + v.getType());
      LOG.info("v.getFileName() : " + v.getFileName());
      
      if (issuable != null) {
        Rule matchedRule = getRuleFromRepository(rulesFromRepository, v.getRuleKey());
        if (matchedRule == null) {
            LOG.info("skip rule : " + v.getRuleKey());
        }
        Issuable.IssueBuilder issueBuilder = issuable.newIssueBuilder()
          .ruleKey(RuleKey.of(PHPCS_REPOSITORY_KEY, matchedRule.getKey()))
          .message(v.getLongMessage())
          .effortToFix(1.0);

        if (v.getLine() > 0) {
          issueBuilder.line(v.getLine());
        }

        issuable.addIssue(issueBuilder.build());
      }
    }
  }

  private ImmutableList<PHPCheck> getCheckVisitors() {
    return ImmutableList.copyOf(checks.all());
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }

  /**
   * phpcsレポートの指摘に対応するSonarQubeルールを取得する
   *
   * 事情（※）によりphpcsレポートの指摘ルールのキー名と、SonarQubeに登録されているキー名は一致しない。
   * phpcs側のキー名に前方一致するSonarQube側キーが存在する場合、そのキーに対応するRuleオブジェクトを返す。
   *
   * ※ 詳しくはREADME.mdを参照のこと。
   *
   * @param Collection<Rule> rulesFromRepository SonarQubeに登録されているphpcsルールのコレクション
   * @param String           ruleKeyFromReport   指摘ルールのキー名
   *
   * @return Rule 対応するルール。存在しない場合は <code>null</code> を返す。
   */
  private Rule getRuleFromRepository(Collection<Rule> rulesFromRepository, String ruleKeyFromReport) {
    for (Rule rule : rulesFromRepository) {
      if (ruleKeyFromReport.startsWith(rule.getKey())) {
        return rule;
      }
    }
   return null;
  }
}
