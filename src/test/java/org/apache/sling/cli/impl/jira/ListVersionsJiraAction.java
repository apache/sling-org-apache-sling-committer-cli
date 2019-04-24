package org.apache.sling.cli.impl.jira;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.io.IOUtils;

import com.sun.net.httpserver.HttpExchange;

public class ListVersionsJiraAction implements JiraAction {

    @Override
    public boolean tryHandle(HttpExchange ex) throws IOException {
        
        if ( !ex.getRequestMethod().equals("GET") ||
                !ex.getRequestURI().getPath().equals("/jira/rest/api/2/project/SLING/versions")) {
            return false;
        }
        
        ex.sendResponseHeaders(200, 0);
        try ( InputStream in = getClass().getResourceAsStream("/jira/versions.json");
                OutputStream out = ex.getResponseBody() ) {
            IOUtils.copy(in, out);
        }
        
        return true;
    }
}
