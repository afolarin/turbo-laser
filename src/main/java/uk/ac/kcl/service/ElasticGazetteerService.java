package uk.ac.kcl.service;

import org.apache.ivy.plugins.version.Match;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import uk.ac.kcl.model.Document;
import uk.ac.kcl.utils.MatchingWindow;
import uk.ac.kcl.utils.StringTools;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by rich on 06/06/16.
 */
@Service
@Profile("deid")
public class ElasticGazetteerService {


    @Autowired
    @Qualifier("sourceDataSource")
    DataSource sourceDataSource;

    @Autowired
    private Environment env;

    JdbcTemplate jdbcTemplate;

    @PostConstruct
    private void init(){
        this.jdbcTemplate = new JdbcTemplate(sourceDataSource);
    }

    public String deIdentify(String document, String docPrimaryKey){
        double levDistance = Double.valueOf(env.getProperty("levDistance"));
        List<Pattern> patterns = null;
        List<String> strings = getStrings(docPrimaryKey);
        patterns = getPatterns(strings,document,levDistance);
        String str2="";
        List<MatchResult> results = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(document);
            while (matcher.find()){
                results.add(matcher.toMatchResult());

            }

        }
        return replaceStrings(results, document);
    }

    private String replaceStrings(List<MatchResult> results, String document) {
        StringBuffer sb = new StringBuffer(document);
        for(MatchResult m : results) {
            int startOffset =m.start();
            int endOffset = m.end();
            StringBuffer outputBuffer = new StringBuffer();
            for (int i = 0; i < (endOffset - startOffset); i++) {
                outputBuffer.append("X");
            }
            sb.replace(startOffset, endOffset, outputBuffer.toString());
        }
        return sb.toString();
    }

    private List<Pattern> getPatterns(List<String> strings, String document, double levDistance) {

        List<Pattern> patterns = new ArrayList<>();

        for(String string : strings) {
            for (String approximateMatch : StringTools.getApproximatelyMatchingStringList(document, string)){
                patterns.add(Pattern.compile(Pattern.quote(approximateMatch),Pattern.CASE_INSENSITIVE));
            }
            for ( MatchingWindow window : StringTools.getMatchingWindowsAboveThreshold(document, string, levDistance) ) {
                if (StringTools.isNotTooShort(string)){
                    patterns.add(Pattern.compile(Pattern.quote(window.getMatchingText()),Pattern.CASE_INSENSITIVE));
                }
            }
            for ( String word : StringTools.splitIntoWordsWithLengthHigherThan(string, Integer.valueOf(env.getProperty("minWordLength")))) {
                patterns.add(Pattern.compile(Pattern.quote(word),Pattern.CASE_INSENSITIVE));
            }
        }
        return patterns;
    }
    private List<String> getStrings(String docPrimaryKey){
        String sql = env.getProperty("termsSQLFront");
        sql = sql + " '" + docPrimaryKey + "' ";
        sql = sql + env.getProperty("termsSQLBack");
        return jdbcTemplate.queryForList(sql, String.class);
    }
}