package org.exist.examples.xmldb;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import javax.xml.transform.OutputKeys;

import org.xmldb.api.DatabaseManager;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Database;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.modules.XMLResource;

import org.exist.xmldb.XQueryService;
import org.exist.xmldb.CompiledExpression;

/**
 *  Reads an XQuery file and executes it. To run this example enter: 
 * 
 *  bin/run.sh examples.xmldb.XQueryExample xquery
 *  
 *  in the root directory of the distribution.
 *
 *@author     Wolfgang Meier <meier@ifs.tu-darmstadt.de>
 *@created    20. September 2002
 */
public class XQueryExample {

    protected static String URI = "xmldb:exist://localhost:8080/exist/xmlrpc";

    protected static String driver = "org.exist.xmldb.DatabaseImpl";

    /**
     * Read the xquery file and return as string.
     */
    protected static String readFile(String file) throws IOException {
    	BufferedReader f = new BufferedReader(new FileReader(file));
    	String line;
    	StringBuffer xml = new StringBuffer();
    	while((line = f.readLine()) != null)
    		xml.append(line);
    	f.close();
    	return xml.toString();
    }
    
    public static void main( String args[] ) {
        try {
            if ( args.length < 1 )
                usage();

            Class cl = Class.forName( driver );
            Database database = (Database) cl.newInstance();
            database.setProperty( "create-database", "true" );
            DatabaseManager.registerDatabase( database );
            
            String query = readFile(args[0]);
            
            // get root-collection
            Collection col =
                DatabaseManager.getCollection( URI + "/db" );
            // get query-service
            XQueryService service =
                (XQueryService) col.getService( "XQueryService", "1.0" );
            
            // set pretty-printing on
            service.setProperty( OutputKeys.INDENT, "yes" );
            service.setProperty( OutputKeys.ENCODING, "UTF-8" );

            CompiledExpression compiled = service.compile( query );
            
            long start = System.currentTimeMillis();
            
            // execute query and get results in ResourceSet
            ResourceSet result = service.execute( compiled );

            long qtime = System.currentTimeMillis() - start;
            start = System.currentTimeMillis();

            for ( int i = 0; i < (int) result.getSize(); i++ ) {
                XMLResource resource = (XMLResource) result.getResource( (long) i ); 
                System.out.println( resource.getContent().toString() );
            }
            long rtime = System.currentTimeMillis() - start;
			System.out.println("hits:          " + result.getSize());
            System.out.println("query time:    " + qtime);
            System.out.println("retrieve time: " + rtime);
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }


    protected static void usage() {
        System.out.println( "usage: examples.xmldb.XQueryExample xquery-file" );
        System.exit( 0 );
    }
}

