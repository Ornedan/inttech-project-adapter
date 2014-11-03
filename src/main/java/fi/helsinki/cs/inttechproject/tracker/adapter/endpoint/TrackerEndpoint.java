package fi.helsinki.cs.inttechproject.tracker.adapter.endpoint;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import fi.helsinki.cs.inttechproject.tracker.adapter.TrackerConnection;
import fi.helsinki.cs.inttechproject.tracker.adapter.TrackerConnection.EyeData;

@ServerEndpoint("/tracker")
public class TrackerEndpoint {
    
    private static final Gson GSON = new Gson();
    private final Logger logger;
    
    private TrackerConnection conn;
    
    /** Currently open websocket sessions. */
    private static final Set<Session> sessions = Collections
            .synchronizedSet(new HashSet<>());


    public TrackerEndpoint() {
        logger = LogManager.getLogger(TrackerEndpoint.class);
    }

    private synchronized TrackerConnection getConn() {
        if(conn == null)
            conn = new TrackerConnection();
        
        return conn;
    }
    
    private void sendEyeData(EyeData data) {
        for(Session session: sessions)
            if(session.isOpen())
                try {
                    session.getBasicRemote().sendText(GSON.toJson(data));
                } catch(IOException e) {
                    logger.error("Exception while sending eye data", e);
                }
    }
    
    // Websocket stuff:
    
    @OnOpen
    public void onOpen(final Session session) {
        sessions.add(session);
    }

    @OnClose
    public void onClose(final Session session) {
        sessions.remove(session);
    }

    @OnMessage
    public void onMessage(final Session session, final String msg) {
        switch (msg) {
        case "connect":
            getConn().connect();
            break;
        case "disconnect":
            getConn().disconnect();
            break;
        case "calibrate":
            getConn().calibrate();
            break;
        case "start-stream":
            getConn().startData((EyeData data) -> {
                sendEyeData(data);
            });
            break;
        case "end-stream":
            getConn().stopData();
            break;
        default:
            logger.error("Got unknown command: {}", msg);
        }
    }
}
