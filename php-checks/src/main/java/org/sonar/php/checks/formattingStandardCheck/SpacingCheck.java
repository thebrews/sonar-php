/*
 * SonarQube PHP Plugin
 * Copyright (C) 2010 SonarSource and Akram Ben Aissi
 * dev@sonar.codehaus.org
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
package org.sonar.php.checks.formattingstandardcheck;

import com.google.common.base.Preconditions;
import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.GenericTokenType;
import com.sonar.sslr.api.Token;
import com.sonar.sslr.api.TokenType;
import org.sonar.php.api.PHPKeyword;
import org.sonar.php.api.PHPPunctuator;
import org.sonar.php.checks.FormattingStandardCheck;
import org.sonar.php.parser.PHPGrammar;

public class SpacingCheck {

  public void visitNode(FormattingStandardCheck formattingCheck, AstNode node) {
    if (formattingCheck.isOneSpaceBetweenRParentAndLCurly && node.is(PHPPunctuator.RPARENTHESIS)) {
      checkSpaceBetweenRParentAndLCurly(formattingCheck, node);
    }
    if (formattingCheck.isOneSpaceAfterComma && node.is(PHPGrammar.PARAMETER_LIST, PHPGrammar.FUNCTION_CALL_PARAMETER_LIST)) {
      checkSpaceForComma(formattingCheck, node);
    }
    if (formattingCheck.isNoSpaceAfterMethodName && node.is(PHPGrammar.FUNCTION_DECLARATION, PHPGrammar.METHOD_DECLARATION, PHPGrammar.FUNCTION_CALL_PARAMETER_LIST)) {
      checkSpaceAfterFunctionName(formattingCheck, node);
    }
    if (formattingCheck.isNoSpaceParenthesis && node.is(PHPPunctuator.RPARENTHESIS)) {
      checkSpaceInsideParenthesis(formattingCheck, node);
    }
  }

  private void checkSpaceInsideParenthesis(FormattingStandardCheck formattingCheck, AstNode rcurly) {
    AstNode lcurly = rcurly.getParent().getFirstChild(PHPPunctuator.LPARENTHESIS);
    Token lcurlyNextToken = lcurly.getNextAstNode().getToken();
    Token rculyPreviousToken = rcurly.getPreviousAstNode().getLastToken();

    boolean isLCurlyOK = !isOnSameLine(lcurlyNextToken, lcurly.getToken()) || getNbSpaceBetween(lcurly.getToken(), lcurlyNextToken) == 0;
    boolean isRCurlyOK = !isOnSameLine(rculyPreviousToken, rcurly.getToken()) || getNbSpaceBetween(rculyPreviousToken, rcurly.getToken()) == 0;



    if (!isLCurlyOK && isRCurlyOK) {
      formattingCheck.reportIssue("Remove all space after the opening parenthesis.", lcurly);
    } else if (isLCurlyOK && !isRCurlyOK) {
      formattingCheck.reportIssue("Remove all space before the closing parenthesis.", rcurly);
    } else if (!isLCurlyOK && !isRCurlyOK) {
      formattingCheck.reportIssue("Remove all space after the opening parenthesis and before the closing parenthesis.", lcurly);
    }
  }

  /**
   * Check there is not space between a function's name and the opening parenthesis.
   */
  private void checkSpaceAfterFunctionName(FormattingStandardCheck formattingCheck, AstNode node) {
    Token lParenToken = node.getFirstChild(PHPPunctuator.LPARENTHESIS).getToken();
    Token funcNameToken = node.is(PHPGrammar.FUNCTION_CALL_PARAMETER_LIST) ?
      node.getPreviousAstNode().getLastToken() : node.getFirstChild(GenericTokenType.IDENTIFIER).getToken();

    if (getNbSpaceBetween(funcNameToken, lParenToken) != 0) {
      formattingCheck.reportIssue("Remove all space between the method name \"" + funcNameToken.getOriginalValue() + "\" and the opening parenthesis.", node);
    }
  }

  /**
   * Check space around the arguments'
   */
  private void checkSpaceForComma(FormattingStandardCheck formattingCheck, AstNode node) {
    int msgIndex = -1;
    String[] msg = {
      "Remove any space before comma separated arguments.",
      "Put exactly one space after comma separated arguments.",
      "Remove any space before comma separated arguments and put exactly one space after comma separated arguments."
    };
    for (AstNode comma : node.getChildren(PHPPunctuator.COMMA)) {
      Token commaToken = comma.getToken();
      Token nextToken = comma.getNextSibling().getToken();
      Token previousToken = comma.getPreviousSibling().getLastToken();

      if (isOnSameLine(previousToken, commaToken, nextToken)) {
        boolean isSpaceBeforeOK = getNbSpaceBetween(previousToken, commaToken) == 0;
        boolean isSpaceAfterOK = getNbSpaceBetween(commaToken, nextToken) == 1;

        if (!isSpaceBeforeOK && isSpaceAfterOK && msgIndex < 0) {
          msgIndex = 0;
        } else if (isSpaceBeforeOK && !isSpaceAfterOK && msgIndex < 0) {
          msgIndex = 1;
        } else if (!isSpaceBeforeOK && !isSpaceAfterOK) {
          msgIndex = 2;
          break;
        }
      }
    }
    if (msgIndex > -1) {
      formattingCheck.reportIssue(msg[msgIndex], node);
    }
  }

  /**
   * Check that there is exactly one space between a closing parenthesis and a opening curly brace.
   */
  private void checkSpaceBetweenRParentAndLCurly(FormattingStandardCheck formattingCheck, AstNode rParenthesis) {
    Token nextToken = rParenthesis.getNextAstNode().getToken();
    Token rParenToken = rParenthesis.getToken();

    if (isType(nextToken, PHPPunctuator.LCURLYBRACE)) {
      int nbSpace = getNbSpaceBetween(rParenToken, nextToken);

      if (nbSpace != 1 && isOnSameLine(rParenToken, nextToken)) {
        formattingCheck.reportIssue(buildIssueMsg(nbSpace, "between the closing parenthesis and the opening curly brace."), rParenthesis);
      }
    }
  }

  protected String buildIssueMsg(int nbSpace, String end) {
    return (new StringBuilder()).append("Put ")
      .append(nbSpace > 1 ? "only " : "")
      .append("one space ")
      .append(end).toString();
  }

  protected boolean isType(Token token, TokenType... types) {
    boolean isOneOfType = false;
    for (TokenType type : types) {
      isOneOfType |= token.getType().equals(type);
    }
    return isOneOfType;
  }

  /**
   * Returns true if all the tokens given as parameters are on the same line.
   */
  protected boolean isOnSameLine(Token... tokens) {
    Preconditions.checkArgument(tokens.length > 0);

    int lineRef = tokens[0].getLine();
    for (Token token : tokens) {
      if (token.getLine() != lineRef) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns number of space between the 2 tokens.
   */
  protected int getNbSpaceBetween(Token token1, Token token2) {
    int token1EndColumn = token1.getColumn() + (token1.getValue().length() - 1);
    int tok2StartColumn = token2.getColumn();

    return tok2StartColumn - token1EndColumn - 1;
  }

}