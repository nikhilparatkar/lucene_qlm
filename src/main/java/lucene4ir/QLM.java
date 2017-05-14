package lucene4ir;

import lucene4ir.utils.TokenAnalyzerMaker;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import javax.xml.bind.JAXB;
import java.io.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.Comparator;

/**
 * Created by nikhil on 4/03/17.
 */
public class QLM {

    public QLMParams p;
    private Analyzer analyzer;
    private IndexReader reader;
    private IndexSearcher searcher;
    public long collectionSize;

    /*
    * Constructor that:
    * 1)Reads parameters from the parameter file
    * 2)Creates an object of index reader class
    * 3)Creates an object of searcher class
    * 4)Calculates total number of unique words in the collection
    * */
    public QLM(String paramFile)
    {
        System.out.println("Retrieval App\nQuery Likelihood Model ");
        readParamsFromFile(paramFile);

        try{
            reader = DirectoryReader.open(FSDirectory.open( new File(p.indexName).toPath()) );
            searcher = new IndexSearcher(reader);
            //Document doc = reader.document(2317);
            ///System.out.println(doc.toString());
            int num_doc = reader.numDocs();
            System.out.println("number of docs: "+String.valueOf(num_doc));
            collectionSize = getCollectionSize();
        }catch(Exception e){
            System.out.println(" Exception:" + e.getClass() + "\n" + e.getMessage());
        }
    }

    /*
    * Get TF of a term over the entire collection
    * */
    public long getTF(String termText) throws IOException {
        Term term = new Term("content", termText);
        long termFreq = reader.totalTermFreq(term);
        return termFreq;
    }

    /*
    * Get DF of a term
    * */
    public long getDF(String termText) throws IOException{
        Term term = new Term("content", termText);
        long docFreq = reader.docFreq(term);
        return docFreq;
    }

    /*
    * Calculates total number of unique words in the entire collection
    * */
    public long getCollectionSize() throws IOException {
        long total=0;
        Set<String> fieldList = new HashSet<>();
        fieldList.add("content");
        Document doc;
        MemoryIndex mi;
        IndexReader mr;
        Terms t;
        for(int i=0;i<reader.numDocs()-1;i++) {
            Document document = reader.document(i);
            int docId = Integer.parseInt(document.get("docnum"));
            doc = reader.document(docId, fieldList);
            mi = MemoryIndex.fromDocument(doc, new StandardAnalyzer());
            mr = mi.createSearcher().getIndexReader();
            t = mr.leaves().get(0).reader().terms("content");


            if ((t != null) && (t.size()>0)) {
                total+=t.size();
            }

            fieldList = new HashSet<>();
            fieldList.add("content");
            doc=null;
            mi=null;
            mr=null;
            t=null;
        }
        //System.out.println("Collection size: "+total);
        return total;
    }

    public void buildTermVector(int docid) throws IOException {
        Set<String> fieldList = new HashSet<>();
        fieldList.add("content");
        System.out.println("Building term vector");
        Document doc = reader.document(docid, fieldList);
        //System.out.println(doc.toString());
        MemoryIndex mi = MemoryIndex.fromDocument(doc, new StandardAnalyzer());
        IndexReader mr = mi.createSearcher().getIndexReader();

        Terms t = mr.leaves().get(0).reader().terms("content");

        if ((t != null) && (t.size()>0)) {
            TermsEnum te = t.iterator();
            BytesRef term = null;
            System.out.println();
            System.out.println(t.size());

            while ((term = te.next()) != null) {
                System.out.println("BytesRef: " + term.utf8ToString());
                System.out.println("docFreq: " + te.docFreq());
                System.out.println("totalTermFreq: " + te.totalTermFreq());

            }

        }
    }

    /*
    * Read parameters from the parameter file provided as an argument to the program
    * */
    public void readParamsFromFile(String paramFile)
    {
        try {
            p = JAXB.unmarshal(new File(paramFile), QLMParams.class);
        } catch (Exception e){
            System.out.println(" caught a " + e.getClass() +
                    "\n with message: " + e.getMessage());
            System.exit(1);
        }

        if (p.resultFile == null){
            p.resultFile = p.runTag+"_results.res";
        }

        System.out.println("Path to index: " + p.indexName);
        System.out.println("Query File: " + p.queryFile);
        System.out.println("Result File: " + p.resultFile);
        System.out.println("Model: " + p.model);
        System.out.println("Lambda: "+ p.lambda);
        System.out.println("Max Results: " + p.maxResults);

        if (p.runTag == null){
            p.runTag = p.model.toLowerCase();
        }

        if (p.tokenFilterFile != null){
            TokenAnalyzerMaker tam = new TokenAnalyzerMaker();
            analyzer = tam.createAnalyzer(p.tokenFilterFile);
        }
        else{
            analyzer = LuceneConstants.ANALYZER;
        }
    }

    /*  Iterate through all the documents to calculate probability score
    *   Save the scores in a hashmap
    */
    private HashMap calculateMLE(String query) throws IOException {
        String[] terms=query.split(" ");
        float value;
        HashMap docScores=new HashMap<Integer, Float>();

        for(int i=0;i<reader.numDocs()-1;i++)
        {
            Document doc = reader.document(i);
            int docId =Integer.parseInt(doc.get("docnum"));
            value = calculateProbability(terms,docId);

            if(value>0)
                docScores.put(docId+1,value);
            value = 0;
        }
        return docScores;
    }

    private float calculateProbability(String[] terms, int docId) throws IOException {
        long Ld=0;
        float tf=0;
        float result=1;
        float temp;
        Map<String,Float> TFs=new HashMap<>();
        Set<String> fieldList = new HashSet<>();

        for(String item : terms)
        {
            TFs.put(item,(float)0);
        }

        fieldList.add("content");

        Document doc = reader.document(docId, fieldList);
        //System.out.println(doc.toString());
        MemoryIndex mi = MemoryIndex.fromDocument(doc, new StandardAnalyzer());
        IndexReader mr = mi.createSearcher().getIndexReader();

        Terms t = mr.leaves().get(0).reader().terms("content");
        /*Check if the document has any content
        * */
        if ((t != null) && (t.size()>0)) {
            TermsEnum te = t.iterator();
            BytesRef term = null;

            Ld = t.size();
            /*
            * Iterate over all the query terms to calculate 'Pmle'
            * */

            while ((term = te.next()) != null) {
                for(String token: terms)
                {
                    if (token.equals(term.utf8ToString())) {
                        tf = te.totalTermFreq();
                        TFs.put(token,tf);
                    }
                }
                tf = 0;
            }

        }
        else
            return 0;

        /*Calculate P(d|q) by iterating over all the terms in the
        * query and multiplying the Pmle scores
        * */
        for(String key:TFs.keySet())
        {
            temp = ((p.lambda * TFs.get(key)/Ld)+((1-p.lambda)*getTF(key)/collectionSize));
            if(temp!=0)
                result = result*temp;
        }
        if(result!=1)
            return result;
        else
            return 0;
    }

    /*
    * Get queries from the query file and calculate document scores for each
    * write the results in the result file specified in the params
    * */
    private void processQueryFile() {
        HashMap<Integer,Float> result;
        HashMap<Integer,Float> sortedResult;
        try{
            BufferedReader br = new BufferedReader(new FileReader(p.queryFile));
            File file = new File(p.resultFile);
            FileWriter fw = new FileWriter(file);
            try{
                String line=br.readLine();
                while (line != null){
                    String[] parts = line.split(" ");
                    String qno = parts[0];
                    String queryTerms = "";
                    for (int i=1; i<parts.length; i++)
                        queryTerms = queryTerms + " " + parts[i];

                    result = calculateMLE(queryTerms.toLowerCase().trim());

                    sortedResult = sortResults(result);
                    int n = Math.min(p.maxResults, sortedResult.keySet().size());
                    int i=0;
                    for(Integer key: sortedResult.keySet())
                    {
                        if(i>=n)
                            break;
                        fw.write(qno + " QO " + key + " " + (i+1) + " " + sortedResult.get(key) + " " + p.runTag);
                        fw.write(System.lineSeparator());
                        i++;
                    }

                    line=br.readLine();
                }
            }finally {
                br.close();
                fw.close();
            }
        }catch(Exception e){
            System.out.println(" caught a " + e.getClass() +
                    "\n with message: " + e.getMessage());
        }
    }

    /*
    * Sort the results in the hashmap by descending order
    * */
    public static HashMap sortResults(HashMap map)
    {
        List list = new LinkedList(map.entrySet());

        Collections.sort(list, new Comparator() {
            public int compare(Object o1, Object o2) {
                return ((Comparable) ((Map.Entry) (o2)).getValue())
                        .compareTo(((Map.Entry) (o1)).getValue());
            }
        });

        HashMap sortedHashMap = new LinkedHashMap();
        for (Iterator it = list.iterator(); it.hasNext();) {
            Map.Entry entry = (Map.Entry) it.next();
            sortedHashMap.put(entry.getKey(), entry.getValue());
        }
        return sortedHashMap;
    }

    public static void main(String[] args) throws IOException {
        /* Get parameter file */
        String retrievalParamFile = "";
        try {
            retrievalParamFile = args[0];
        } catch (Exception e) {
            System.out.println(" caught a " + e.getClass() +
                    "\n with message: " + e.getMessage());
            System.exit(1);
        }
        /* Initialize Query Likelihood Model object and run queries */
        QLM obj=new QLM(retrievalParamFile);

        obj.processQueryFile();
    }



}
class QLMParams {
    public String indexName;
    public String queryFile;
    public String resultFile;
    public String model;
    public float lambda;
    public int maxResults;
    public float lam;
    public String runTag;
    public String tokenFilterFile;
}
