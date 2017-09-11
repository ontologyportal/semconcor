package com.articulate.semconcor;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.articulate.nlp.pipeline.Pipeline;
import com.articulate.nlp.pipeline.SentenceUtil;
import com.articulate.nlp.semRewrite.Literal;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.util.CoreMap;

import com.articulate.nlp.pipeline.Pipeline;
import com.articulate.nlp.corpora.wikipedia.SimpleSentenceExtractor;
/*
Author: Adam Pease apease@articulatesoftware.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program ; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston,
MA  02111-1307 USA

Read wikipedia text in wikimedia format using SimpleSentenceExtractor
and store in an H2 SQL database

java -jar h2*.jar

to start the server
*/

public class SemConcor {

    public static final int tokensMax = 25;

    /****************************************************************
     */
    private static boolean initialCapital(List<CoreLabel> tokens) {

        return Character.isUpperCase(tokens.get(0).originalText().charAt(0));
    }

    /****************************************************************
     * exclude questions
     */
    private static boolean endPunctuation(List<CoreLabel> tokens) {

        String last = tokens.get(tokens.size()-1).originalText();
        return (last.equals(".") || last.equals("!"));
    }

    /****************************************************************
     * Take one line of plan text, parse it into dependencies and add
     * indexes for the raw tokens and the tokens in the dependency parse
     * into the database. The DB tables are
     * content - the sentence and its dependency parse
     * index - tokens and the sentences in which they are found
     * depindex - tokens and the dependencies in which they are found
     */
    public static void extractOneLine(Connection conn, String line, Pipeline p, int limit, String file, int linenum) {

        Annotation wholeDocument = new Annotation(line);
        p.pipeline.annotate(wholeDocument);
        List<CoreMap> sentences = wholeDocument.get(CoreAnnotations.SentencesAnnotation.class);
        int sentnum = 0;
        for (CoreMap sentence : sentences) {
            sentnum++;
            List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
            if (tokens.size() > 2 && tokens.size() < limit &&
                    initialCapital(tokens) && endPunctuation(tokens)) {
                List<String> dependenciesList = SentenceUtil.toDependenciesList(sentence);
                try {

                    String str = "insert into content (cont,dependency,file,sentnum,linenum) values ";
                    Statement stmt = conn.createStatement();
                    stmt.execute(str + "('" + sentence.toString() + "', '" +
                            dependenciesList.toString() + "', '" + file + "', '" +
                            sentnum + "', '" + linenum + "');");

                    for (CoreLabel tok : tokens) {
                        str = "insert into index (token,file,sentnum,linenum) values ";
                        stmt = conn.createStatement();
                        stmt.execute(str + "('" + tok.originalText() + "', '" +
                                file + "', '" + sentnum + "', '" + linenum + "');");

                        str = "select count from counts where token='" + tok.originalText() + "';";
                        stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery(str);
                        while (rs.next()) {
                            int count = rs.getInt("token");
                        }
                        int count = 0;
                        str = "insert into content (token,count) values ";
                        stmt = conn.createStatement();
                        stmt.execute(str + "('" + tok.originalText() + "', '" + count + "');");
                    }
                    for (String s : dependenciesList) {
                        Literal l = new Literal(s);

                        str = "insert into depindex (token,file,sentnum,linenum) values ";
                        stmt = conn.createStatement();
                        stmt.execute(str + "('" + l.arg1 + "', '" +
                                file + "', '" + sentnum + "', '" + linenum + "');");

                        str = "insert into depindex (token,file,sentnum,linenum) values ";
                        stmt = conn.createStatement();
                        stmt.execute(str + "('" + l.arg2 + "', '" +
                                file + "', '" + sentnum + "', '" + linenum + "');");

                        str = "insert into depindex (token,file,sentnum,linenum) values ";
                        stmt = conn.createStatement();
                        stmt.execute(str + "('" + l.pred + "', '" +
                                file + "', '" + sentnum + "', '" + linenum + "');");
                    }
                }
                catch(Exception e) {
                    System.out.println(e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    /****************************************************************
     * http://www.evanjones.ca/software/wikipedia2text.html
     * http://www.evanjones.ca/software/wikipedia2text-extracted.txt.bz2 plain text of 10M words
     */
    public static void storeWikiText(Connection conn) {

        int count = 0;
        Pipeline p = new Pipeline(true,Pipeline.defaultProp);
        Statement stmt = null;
        String str = "insert into content (cont) values ";
        try {
            String corporaDir = System.getenv("CORPORA");
            String file = "wikipedia2text-extracted.txt";
            InputStream in = new FileInputStream(corporaDir + "/wikipedia/" + file);
            Reader reader = new InputStreamReader(in);
            LineNumberReader lnr = new LineNumberReader(reader);
            String line = null;
            while ((line = lnr.readLine()) != null && count++ < 10) {
                extractOneLine(conn,line,p,tokensMax,file,lnr.getLineNumber());
            }
        }
        catch(Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    /***************************************************************
     */
    public static void test() throws Exception {

        Class.forName("org.h2.Driver");
        Connection conn = DriverManager.getConnection("jdbc:h2:~/test", "sa", "");

        Statement stmt = null;
        String query = "select * from index";
        try {
            stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(query);
            while (rs.next()) {
                String token = rs.getString("TOKEN");
                String filename = rs.getString("FILE");
                int sentNum = rs.getInt("SENTNUM");
                System.out.println(token + "\t" + filename + "\t" + sentNum);
            }
        }
        catch (SQLException e ) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
        finally {
            if (stmt != null)
                stmt.close();
        }
        conn.close();
    }

    /***************************************************************
     */
    public static void main(String[] args) throws Exception {

        Class.forName("org.h2.Driver");
        Connection conn = DriverManager.getConnection("jdbc:h2:~/test", "sa", "");
        storeWikiText(conn);
        conn.close();
    }
}
