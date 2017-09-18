package com.articulate.semconcor;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.articulate.nlp.TFIDF;
import com.articulate.nlp.pipeline.Pipeline;
import com.articulate.nlp.pipeline.SentenceUtil;
import com.articulate.nlp.semRewrite.CNF;
import com.articulate.nlp.semRewrite.Interpreter;
import com.articulate.nlp.semRewrite.Literal;
import com.articulate.sigma.KBmanager;
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

public class Indexer {

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
     */
    private static void storeCount(Connection conn, String tok) {

        try {
            String str = "select count from counts where token='" + tok + "';";
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(str);

            if (!rs.next()) {
                str = "insert into counts (token,count) values ";
                stmt = conn.createStatement();
                stmt.execute(str + "('" + tok + "', '1');");
            }
            else {
                int count = rs.getInt("count");
                count++;
                str = "update counts set count='" + count + "' where token='" + tok + "';";
                stmt = conn.createStatement();
                stmt.execute(str);
            }
        }
        catch(Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    /****************************************************************
     */
    private static void storeSentenceToken(Connection conn, String file, int sentnum, int linenum, CoreLabel tok) {

        try {
            String str = "insert into index (token,file,sentnum,linenum) values ";
            Statement stmt = conn.createStatement();
            stmt.execute(str + "('" + tok.originalText() + "', '" +
                    file + "', '" + sentnum + "', '" + linenum + "');");

            storeCount(conn, tok.originalText());
        }
        catch(Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    /****************************************************************
     * exclude questions
     */
    private static void storeDependency(Connection conn, String file, int sentnum, int linenum, String token) {

        try {
            String str = "insert into depindex (token,file,sentnum,linenum) values ";
            Statement stmt = conn.createStatement();
            stmt.execute(str + "('" + token + "', '" +
                    file + "', '" + sentnum + "', '" + linenum + "');");
            storeCount(conn, token);
        }
        catch(Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    /****************************************************************
     * Take one line of plan text, parse it into dependencies and add
     * indexes for the raw tokens and the tokens in the dependency parse
     * into the database. The DB tables are
     * content - the sentence and its dependency parse
     * index - tokens and the sentences in which they are found
     * depindex - tokens and the dependencies in which they are found
     * counts - the number of occurrences of a token, used to order joins
     */
    public static void extractOneLine(Connection conn, String line, Pipeline p, int limit, String file, int linenum) {

        Annotation wholeDocument = new Annotation(line);
        try {
            p.pipeline.annotate(wholeDocument);
        }
        catch (Exception e) {
            System.out.println("Error in Indexer.extractOneLine(): " + e.getMessage());
            e.printStackTrace();
            return;
        }
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

                    for (CoreLabel tok : tokens)
                        storeSentenceToken(conn,file,sentnum,linenum,tok);

                    for (String s : dependenciesList) {
                        Literal l = new Literal(s);
                        storeDependency(conn,file,sentnum,linenum,l.arg1);
                        storeDependency(conn,file,sentnum,linenum,l.arg2);
                        storeDependency(conn,file,sentnum,linenum,l.pred);
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
     * Take one line of plan text, parse it into dependencies augmented
     * by the semantic rewriting system and add
     * indexes for the raw tokens and the tokens in the dependency parse
     * into the database. The DB tables are
     * content - the sentence and its dependency parse
     * index - tokens and the sentences in which they are found
     * depindex - tokens and the dependencies in which they are found
     * counts - the number of occurrences of a token, used to order joins
     */
    public static void extractOneAugmentLine(Interpreter interp, Connection conn, String line,
                                             int limit, String file, int linenum) {


        System.out.println("extractOneAugmentLine(): " + line);
        List<CoreMap> sentences = null;
        try {
            Annotation wholeDocument = new Annotation(line);
            wholeDocument.set(CoreAnnotations.DocDateAnnotation.class, "2017-09-17");
            interp.p.pipeline.annotate(wholeDocument);
            sentences = wholeDocument.get(CoreAnnotations.SentencesAnnotation.class);
        }
        catch (Exception e) {
            System.out.println("Error in extractOneAugmentLine(): " + e.getMessage());
            e.printStackTrace();
            return;
        }
        int sentnum = 0;
        for (CoreMap sentence : sentences) {
            System.out.println("extractOneAugmentLine(): sentence: " + sentence);
            sentnum++;
            List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
            if (tokens.size() > 2 && tokens.size() < limit &&
                    initialCapital(tokens) && endPunctuation(tokens)) {
                CNF cnf = interp.interpretGenCNF(sentence);
                List<String> dependenciesList = cnf.toListString();
                try {
                    String str = "insert into content (cont,dependency,file,sentnum,linenum) values ";
                    Statement stmt = conn.createStatement();
                    str = str + "('" + sentence.toString() + "', '" +
                            dependenciesList.toString() + "', '" + file + "', '" +
                            sentnum + "', '" + linenum + "');";
                    stmt.execute(str);
                    System.out.println("extractOneAugmentLine(): " + str);

                    for (CoreLabel tok : tokens)
                        storeSentenceToken(conn,file,sentnum,linenum,tok);

                    for (String s : dependenciesList) {
                        Literal l = new Literal(s);
                        storeDependency(conn,file,sentnum,linenum,l.arg1);
                        storeDependency(conn,file,sentnum,linenum,l.arg2);
                        storeDependency(conn,file,sentnum,linenum,l.pred);
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

        int maxSent = 10;
        Interpreter interp = new Interpreter();
        KBmanager.getMgr().initializeOnce();
        try {
            interp.initialize();
        }
        catch (Exception e) {
            System.out.println("Error in Indexer.storeWikiText(): " + e.getMessage());
            e.printStackTrace();
            return;
        }
        int count = 0;
        try {
            String corporaDir = System.getenv("CORPORA");
            String file = "wikipedia2text-extracted.txt";
            InputStream in = new FileInputStream(corporaDir + "/wikipedia/" + file);
            Reader reader = new InputStreamReader(in);
            LineNumberReader lnr = new LineNumberReader(reader);
            String line = null;
            long t1 = System.currentTimeMillis();
            while ((line = lnr.readLine()) != null && count++ < maxSent) {
                extractOneAugmentLine(interp,conn,line,tokensMax,file,lnr.getLineNumber());
            }
            double seconds = ((System.currentTimeMillis() - t1) / 1000.0);
            System.out.println("time to process: " + seconds + " seconds");
            System.out.println("time to process: " + (seconds / maxSent) + " seconds per sentence");
        }
        catch(Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    /***************************************************************
     * clear all the database content
     */
    public static void clearDB(Connection conn) throws Exception {

        Statement stmt = null;
        ArrayList<String> commands = new ArrayList<>();
        String query = "delete from index";
        commands.add(query);
        query = "delete from depindex";
        commands.add(query);
        query = "delete from counts";
        commands.add(query);
        query = "delete from content";
        commands.add(query);

        try {
            for (String s : commands) {
                stmt = conn.createStatement();
                boolean res = stmt.execute(s);
            }
        }
        catch (SQLException e ) {
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
        clearDB(conn);
        storeWikiText(conn);
        conn.close();
    }
}
