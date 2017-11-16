/*
 * Copyright (c) 2003, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.javadoc.internal.doclets.formats.html.markup;

import java.io.IOException;
import java.io.Writer;

import jdk.javadoc.internal.doclets.toolkit.Content;
import jdk.javadoc.internal.doclets.toolkit.util.DocletConstants;

/**
 * A builder for HTML script elements.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Script  {
    private final StringBuilder sb;

    /**
     * Creates an empty script.
     */
    public Script() {
        sb = new StringBuilder();
    }

    /**
     * Creates a new script containing the specified code.
     *
     * @param code the code
     */
    public Script(String code) {
        this();
        append(code);
    }

    /**
     * Appends the given code to the content.
     *
     * @param code the code
     * @return this object
     */
    public Script append(CharSequence code) {
        sb.append(code);
        return this;
    }

    /**
     * Appends the given text as a string constant to the content.
     * Characters within the string will be escaped as needed.
     *
     * @param text the text
     * @return this object
     */
    public Script appendStringLiteral(CharSequence text) {
        sb.append(stringLiteral(text, '"'));
        return this;
    }

    /**
     * Appends the given text as a string constant to the content.
     * Characters within the string will be escaped as needed.
     *
     * @param text the text
     * @param quoteChar the quote character to use
     * @return this object
     */
    // The ability to specify the quote character is for backwards
    // compatibility. Ideally, we should simplify the code so that
    // the same quote character is always used.
    public Script appendStringLiteral(CharSequence text, char quoteChar) {
        sb.append(stringLiteral(text, quoteChar));
        return this;
    }

    public Content asContent() {
        ScriptContent scriptContent = new ScriptContent(sb);
        HtmlTree tree = new HtmlTree(HtmlTag.SCRIPT) {
            @Override
            public void addContent(CharSequence s) {
                throw new UnsupportedOperationException();
            }
            @Override
            public void addContent(Content c) {
                if (c != scriptContent) {
                    throw new IllegalArgumentException();
                }
                super.addContent(scriptContent);
            }
        };
        tree.addAttr(HtmlAttr.TYPE, "text/javascript");
        tree.addContent(scriptContent);
        return tree;
    }

    /**
     * Returns a String with escaped special JavaScript characters.
     *
     * @param s String that needs to be escaped
     * @return a valid escaped JavaScript string
     */
    public static String stringLiteral(CharSequence s) {
        return stringLiteral(s, '"');
    }

    /**
     * Returns a String with escaped special JavaScript characters.
     *
     * @param s String that needs to be escaped
     * @param quoteChar the quote character to use for the literal
     * @return a valid escaped JavaScript string
     */
    // The ability to specify the quote character is for backwards
    // compatibility. Ideally, we should simplify the code so that
    // the same quote character is always used.
    public static String stringLiteral(CharSequence s, char quoteChar) {
        if (quoteChar != '"' && quoteChar != '\'') {
            throw new IllegalArgumentException();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(quoteChar);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\b':
                    sb.append("\\b");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\'':
                    sb.append("\\\'");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    if (ch < 32 || ch >= 127) {
                        sb.append(String.format("\\u%04X", (int)ch));
                    } else {
                        sb.append(ch);
                    }
                    break;
            }
        }
        sb.append(quoteChar);
        return sb.toString();
    }

    private static class ScriptContent extends Content {
        private final StringBuilder sb;

        ScriptContent(StringBuilder sb) {
            this.sb = sb;
        }

        @Override
        public void addContent(Content content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addContent(CharSequence code) {
            sb.append(code);
        }

        @Override
        public boolean write(Writer writer, boolean atNewline) throws IOException {
            String s = sb.toString();
            writer.write(s.replace("\n", DocletConstants.NL));
            return s.endsWith("\n");
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }
}
