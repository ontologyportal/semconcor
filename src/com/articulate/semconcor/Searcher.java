package com.articulate.semconcor;

import com.articulate.nlp.semRewrite.*;
import com.articulate.sigma.KBmanager;
import com.articulate.sigma.StringUtil;
import com.google.common.base.Strings;

import java.sql.*;
import java.util.*;

/**
 * Created by apease on 9/12/17.
 */
public class Searcher {

    /***************************************************************
     */
    public static void fetchResultStrings(Connection conn, HashSet<String> result,
                                                     ArrayList<String> sentences,
                                                     ArrayList<String> dependencies) {

        Statement stmt = null;
        try {
            for (String s : result) {
                String[] sar = s.split("#");
                String query = "select cont,dependency from content where file='" + sar[0] +
                        "' and sentnum=" + sar[1] + " and linenum=" + sar[2] + ";";
                stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(query);
                while (rs.next()) {
                    sentences.add(rs.getString("CONT"));
                    dependencies.add(rs.getString("DEPENDENCY"));
                }
            }
        }
        catch (SQLException e ) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    /***************************************************************
     */
    public static HashSet<String> fetchFromIndex(Connection conn, String indexName,
                                                 ArrayList<String> tokens) {

        System.out.println("Searcher.fetchFromIndex(): " + indexName + "\n" + tokens);
        HashSet<String> result = new HashSet<String>();
        Statement stmt = null;
        try {
            for (String s : tokens) {
                String query = "select * from " + indexName + " where token='" + s + "';";
                System.out.println("Searcher.fetchFromIndex(): query: " + query);
                stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(query);
                HashSet<String> newresult = new HashSet<>();
                while (rs.next()) {
                    String filename = rs.getString("FILE");
                    int sentNum = rs.getInt("SENTNUM");
                    int lineNum = rs.getInt("LINENUM");
                    newresult.add(filename + "#" + Integer.toString(sentNum) + "#" + Integer.toString(lineNum));
                }
                if (result.isEmpty())
                    result.addAll(newresult);
                else
                    result.retainAll(newresult);
            }
        }
        catch (SQLException e ) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Searcher.fetchFromIndex(): result: " + result);
        return result;
    }

    /***************************************************************
     * Note that dependency patterns must be simple CNF - no disjuncts
     * and only positive literals
     * returns the indices of all the dependency forms that don't unify
     * with the search dependency form
     * @param dep the dependency pattern to search for.
     * @param dependencies the dependency parses of sentences to check for a match
     * @return a list of indexes to items that don't match the pattern
     */
    public static ArrayList<Integer> matchDependencies(String dep,
                                    ArrayList<String> dependencies) {

        System.out.println("Searcher.matchDependencies(): " + dep + "\n" + dependencies);
        ArrayList<Integer> result = new ArrayList<Integer>();
        if (Strings.isNullOrEmpty(dep))
            return result;
        Lexer lex = new Lexer(dep);
        CNF smallcnf = CNF.parseSimple(lex);
        for (int i = 0; i < dependencies.size(); i++) {
            String onedep = dependencies.get(i);
            onedep = StringUtil.removeEnclosingCharPair(onedep,2,'[',']'); // two layers of brackets
            lex = new Lexer(onedep);
            CNF depcnf = CNF.parseSimple(lex);
            HashMap<String,String> bindings = smallcnf.unify(depcnf);
            if (bindings == null)  // remove all that don't unify
                result.add(i);
            else
                System.out.println("Searcher.matchDependencies(): " + bindings);
        }
        return result;
    }

    /***************************************************************
     */
    public static HashSet<String> fetchIndexes(Connection conn,
                                               ArrayList<String> sentTokens, ArrayList<String> depTokens) {

        System.out.println("fetchIndexes():" + sentTokens + "\n" + depTokens);
        HashSet<String> result = new HashSet<String>();
        if (sentTokens != null && sentTokens.size() > 0)
            result = fetchFromIndex(conn,"index",sentTokens);
        if (result.size() == 0)
            result = fetchFromIndex(conn,"depindex",depTokens);
        if (depTokens != null && depTokens.size() > 0)
            result.retainAll(fetchFromIndex(conn,"depindex",depTokens));
        return result;
    }

    /***************************************************************
     */
    public static ArrayList<String> depToTokens(String dep) {

        System.out.println("Searcher.depToTokens(): " + dep);
        ArrayList<String> result = new ArrayList<String>();
        Lexer lex = new Lexer(dep);
        CNF cnf = CNF.parseSimple(lex);
        for (Clause c : cnf.clauses) {
            for (Literal l : c.disjuncts) {
                if (!Literal.isVariable(l.arg1))
                    result.add(l.arg1);
                if (!Literal.isVariable(l.arg2))
                    result.add(l.arg2);
                if (!Literal.isVariable(l.pred) && !Procedures.isProcPred(l.pred))
                    result.add(l.pred);
            }
        }
        return result;
    }

    /***************************************************************
     */
    public static void search(String phrase, String dep) throws Exception {

        System.out.println("Searcher.search(): " + phrase + "\n" + dep);
        String searchString = phrase;
        String[] ar = searchString.split(" ");
        ArrayList<String> sentTokens = new ArrayList<>();
        sentTokens.addAll(Arrays.asList(ar));

        ArrayList<String> depTokens = new ArrayList<>();
        depTokens = depToTokens(dep);

        Class.forName("org.h2.Driver");
        Connection conn = DriverManager.getConnection("jdbc:h2:~/test", "sa", "");

        Statement stmt = null;
        HashSet<String> result = new HashSet<>();
        try {
            result = fetchIndexes(conn,sentTokens, depTokens);
            System.out.println("search(): indexes: " + result);
            ArrayList<String> sentences = new ArrayList<>();
            ArrayList<String> dependencies = new ArrayList<>();
            fetchResultStrings(conn,result,sentences,dependencies);

            ArrayList<Integer> removeList = matchDependencies(dep,dependencies);
            ArrayList<String> newsent = new ArrayList<>();
            ArrayList<String> newdep = new ArrayList<>();
            for (int i = 0; i < dependencies.size(); i++) {
                if (!removeList.contains(i)) {
                    newsent.add(sentences.get(i));
                    newdep.add(dependencies.get(i));
                }
            }
            for (int i = 0; i < newsent.size(); i++) {
                String s = newsent.get(i);
                String d = newdep.get(i);
                System.out.println("Sentence: " + s);
                System.out.println("Dependency: " + d);
            }
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
        finally {
            if (stmt != null)
                stmt.close();
        }
        conn.close();
    }

    /** ***************************************************************
     * Allows interactive testing of entering a word, phrase, or dependency
     * pattern and returning the matching sentences, or just the first 10
     * and a count if there are more than 10 results
     */
    public static void interactive() {

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
        String input = "";
        String deps = "";
        Scanner scanner = new Scanner(System.in);
        do {
            System.out.print("Enter word or phrase: ");
            input = scanner.nextLine().trim();
            if (!Strings.isNullOrEmpty(input) && (input.equals("exit") || input.equals("quit")))
                return;
            System.out.print("Enter dependency pattern: ");
            deps = scanner.nextLine().trim();
            if (!Strings.isNullOrEmpty(input) || !Strings.isNullOrEmpty(deps)) {
                if (Strings.isNullOrEmpty(input) || (!input.equals("exit") && !input.equals("quit"))) {
                    try {
                        search(input, deps);
                    }
                    catch (Exception e) {
                        System.out.println(e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        } while (!input.equals("exit") && !input.equals("quit"));
    }

    /***************************************************************
     * search for a matching sentence with the first quoted argument
     * being a word or phrase and the second quoted argument being
     * a dependency pattern.
     */
    public static void main(String[] args) throws Exception {

        Class.forName("org.h2.Driver");
        Connection conn = DriverManager.getConnection("jdbc:h2:~/test", "sa", "");
        String searchString = "in";
        if (args != null && args.length > 0 && args[0].equals("-i"))
            interactive();
        else {
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
            if (args != null && args.length > 0)
                searchString = args[0];
            String depString = "sumo(Process,?X)";
            if (args != null && args.length > 1)
                depString = args[1];
            search(searchString,depString);
        }

    }
}
