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

import fi.helsinki.cs.inttechproject.tracker.adapter.EyeData;
import fi.helsinki.cs.inttechproject.tracker.adapter.TrackerConnection;

@ServerEndpoint("/tracker-adapter/tracker")
public class TrackerEndpoint {
    
    private static final Gson GSON = new Gson();
    private final Logger logger;
    
    private static TrackerConnection conn = new TrackerConnection();
    
    /** Currently open websocket sessions. */
    private static final Set<Session> sessions = Collections
            .synchronizedSet(new HashSet<>());


    public TrackerEndpoint() {
        logger = LogManager.getLogger(TrackerEndpoint.class);
    }
    
    private void sendEyeData(EyeData data) {
        for(Session session: sessions)
            if(session.isOpen())
                try {
                    session.getBasicRemote().sendText(GSON.toJson(data));
                } catch(IllegalStateException | IOException e) {
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
        logger.debug("Got websocket command: '{}'", msg);
        switch (msg) {
        case "connect":
            conn.connect();
            break;
        case "disconnect":
            conn.disconnect();
            break;
        case "calibrate":
            conn.calibrate();
            break;
        case "start-stream":
            conn.startData((EyeData data) -> {
                sendEyeData(data);
            });
            break;
        case "end-stream":
            conn.stopData();
            break;
            
        case "setup":
            conn.connect();
            conn.calibrate();
            conn.startData((EyeData data) -> {
                sendEyeData(data);
            });
            break;
            
        default:
            logger.error("Got unknown command: {}", msg);
        }
    }
}
