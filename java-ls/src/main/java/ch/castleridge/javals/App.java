package ch.castleridge.javals;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Main entry point for the Java Language Server
 * Copyright Anysphere Inc.
 */
public class App {
    
    private static final Logger LOGGER = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        try {
            // Disable default logging to avoid interference with LSP communication
            LogManager.getLogManager().reset();
            
            startServer(System.in, System.out);
        } catch (Exception e) {
            LOGGER.severe("Error starting language server: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void startServer(InputStream in, OutputStream out) 
            throws ExecutionException, InterruptedException {
        
        // Create the language server
        JavaLanguageServer server = new JavaLanguageServer();
        
        // Create the launcher
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
            server, 
            in, 
            out
        );
        
        // Connect the client proxy to the server
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        
        // Start listening
        Future<Void> startListening = launcher.startListening();
        
        LOGGER.info("Java Language Server started and listening...");
        
        // Wait for the connection to close
        startListening.get();
    }
}
