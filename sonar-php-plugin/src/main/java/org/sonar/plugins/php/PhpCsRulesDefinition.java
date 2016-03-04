package org.sonar.plugins.php;

import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.server.rule.RulesDefinitionXmlLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PhpCsRulesDefinition implements RulesDefinition {

    private static final Logger LOG = LoggerFactory.getLogger(PhpCsRulesDefinition.class);

    private final RulesDefinitionXmlLoader xmlLoader;

    public PhpCsRulesDefinition(RulesDefinitionXmlLoader xmlLoader) {
        this.xmlLoader = xmlLoader;
    }

    @Override
    public void define(Context context) {
        NewRepository repository = context.createRepository("all-phpcs-rules", "php").setName("My Javascript Analyzer");
        // see javadoc of RulesDefinitionXmlLoader for the format
        xmlLoader.load(repository, getClass().getResourceAsStream("/org/sonar/plugins/php/codesniffer/rules.xml"), "UTF-8");
        repository.done();
    }
}
