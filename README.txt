Introduction
============

Semantic Concordancer

Concordancers are an accepted and valuable part of the tool set of linguists and
lexicographers. They allow the user to see the context of use of a word or
phrase in a corpus. One challenge is that there may be too many results for
short phrases or common words when only a specific context is desired. However,
finding meaningful groupings of usage may be challenging or impractical if it
means enumerating long lists of possible values, such as city names. If a tool
existed that could create some semantic abstractions, it would free the
lexicographer from the need to resort to customized development of analysis
software. To address this need, we have developed a Semantic Concordancer that
uses dependency parsing and the Suggested Upper Merged Ontology (SUMO) to
support linguistic analysis at a level of semantic abstraction above the
original textual elements.
  Users of this work who publish academic papers are asked to cite

Pease, A., and Cheung, A., (2018).  Toward A Semantic Concordancer, to appear.


Linux Installation
==================

Please install https://github.com/ontologyportal/sigmakee and
https://github.com/ontologyportal/sigmanlp first.

cd ~/workspace
git clone https://github.com/ontologyportal/semconcor
cd ~/Programs
wget 'http://www.h2database.com/h2-2017-06-10.zip'
unzip h2-2017-06-10.zip

you'll need to set Indexer.JDBCstring to point to your corpus database and
modify the main() method of Indexer to read your corpus.  Currently,
the system can read from

- the FCE corpus, https://www.ilexir.co.uk/datasets/index.html
- wikipedia - http://www.evanjones.ca/software/wikipedia2text-extracted.txt.bz2 plain text of 10M words
- or our Hong Kong court judgment corpus, which is not generally available.

Note that the system is currently pretty slow, and even indexing the FCE corpus will take days

Assuming you've downloaded the FCE corpus to ~/corpora/fce-released-dataset
First create the empty database

java -cp ~/Programs/h2/bin/h2*.jar org.h2.tools.RunScript -url jdbc:h2:~/corpora/FCE -script ~/workspace/semconcor/script.sql

Then run the indexer

java -Xmx2G -cp /home/apease/workspace/semconcor/build/classes:
  /home/apease/workspace/semconcor/build/lib/*:
  /home/apease/workspace/sigmanlp/build/lib/* com.articulate.semconcor.Indexer

Finally, you can run the concordancer on the command line

java -Xmx2G -cp /home/apease/workspace/semconcor/build/classes:
  /home/apease/workspace/semconcor/build/lib/*:
  /home/apease/workspace/sigmanlp/build/lib/* com.articulate.semconcor.Searcher -i

or start it up in tomcat

$CATALINA_HOME/bin/startup.sh

and point your browser at

localhost:8080/semconcor/semconcor.jsp
