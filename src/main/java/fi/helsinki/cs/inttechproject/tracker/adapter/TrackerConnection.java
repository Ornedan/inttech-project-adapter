package fi.helsinki.cs.inttechproject.tracker.adapter;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TrackerConnection {

    // Tracker server
    static String host = "localhost";
    static int port = 4242;

    private final Logger logger;
    
    // Socket and it's I/O tools
    private Socket socket;
    private PrintWriter out;
    private Scanner in;
    
    // Thread for forwarding data from the tracker
    private Thread listeningThread;
    
    
    public TrackerConnection() {
        logger = LogManager.getLogger(TrackerConnection.class);
    }

    public void connect() {
        logger.debug("Attempting to connect to the tracker server");
        try {
            socket = new Socket(host, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new Scanner(socket.getInputStream());
            logger.info("Connected to the tracker server");
        } catch (IOException e) {
            logger.error("Connecting to tracker server failed", e);
            disconnect();
        }
    }
    
    public void disconnect() {
        logger.info("Disconnecting from the tracker server");
        if (socket != null)
            try {
                socket.close();
                socket = null;
            } catch (IOException e) {
                logger.error("Exception while cleaning up failed connection", e);
            }
        if (in != null) {
            in.close();
            in = null;
        }
        if (out != null) {
            out.close();
            out = null;
        }
    }
    
    /**
     * Calibration routine works as follows:
     * 
     * - Show camera screen so the user can see where to place their head
     * - Wait until the tracker has reported both eyes OK for 3 continous seconds
     * - Hide camera screen
     * - Run calibration process
     */
    public void calibrate() {
        logger.info("Starting calibration process");
        waitUntilEyes();

        set("CALIBRATE_SHOW", "1");
        set("CALIBRATE_START", "1");

        do {
            String line = in.nextLine();
            //System.out.println(line);

            if (line.contains("ID=\"CALIB_RESULT\""))
                break;
        } while (true);
        
        // Show summary
        String summary = get("CALIBRATE_RESULT_SUMMARY");
        logger.info("Calibration result: {}", summary);

        set("CALIBRATE_START", "0");
        set("CALIBRATE_SHOW", "0");
    }

    void waitUntilEyes() {
        logger.info("Waiting until tracker sees eyes");
        // Show camera screen
        set("TRACKER_DISPLAY", "1");

        // Eyes
        set("ENABLE_SEND_EYE_LEFT", "1");
        set("ENABLE_SEND_EYE_RIGHT", "1");

        // Start
        set("ENABLE_SEND_DATA", "1");

        // Wait until both eyes (and pupils?) are noted valid for a continuous
        // 3 second span
        Pattern leye = Pattern.compile("LEYEV=\"(\\d+)\"");
        Pattern reye = Pattern.compile("REYEV=\"(\\d+)\"");

        Long vstart = null;

        do {
            String line = in.nextLine();
            //System.out.println(line);

            Matcher lm = leye.matcher(line);
            Matcher rm = reye.matcher(line);

            lm.find();
            rm.find();

            String lv = lm.group(1);
            String rv = lm.group(1);
            //System.out.printf("lv: %s; rv: %s\n", lv, rv);

            if ("1".equals(lv) && "1".equals(rv)) { // Valid?
                if (vstart == null) { // New validity period?
                    vstart = System.currentTimeMillis();
                    logger.debug("Tracker identified both eyes");
                }
            } else {
                if(vstart != null)
                    logger.debug("Tracker lost eyes");
                
                vstart = null;
            }
        } while (vstart == null || (System.currentTimeMillis() - vstart) <= 3 * 1000);
        
        logger.info("Tracker has seen both eyes for {} msec, OK", System.currentTimeMillis() - vstart);

        // Shut down data stream
        set("ENABLE_SEND_DATA", "0");
        set("ENABLE_SEND_EYE_LEFT", "0");
        set("ENABLE_SEND_EYE_RIGHT", "0");

        // Hide camera
        set("TRACKER_DISPLAY", "0");
    }

    void set(String opt, String to) {
        out.print("<SET ID=\"" + opt + "\" STATE=\"" + to + "\" />\r\n");
        out.flush();
        
        String resp = in.nextLine();
        logger.debug("Set {} to {}, got response {}", opt, to, resp);
    }

    String get(String opt) {
        out.print("<GET ID=\"" + opt + "\" />\r\n");
        out.flush();

        return in.nextLine();
    }

    public void startData(TrackerListener listener) {
        if(listeningThread != null) {
            logger.error("Attempt to start data stream when it's already active");
            return;
        }
        
        logger.info("Requesting data stream from tracker server");
        set("ENABLE_SEND_TIME", "1");
        set("ENABLE_SEND_POG_BEST", "1");
        set("ENABLE_SEND_POG_FIX", "1");
        set("ENABLE_SEND_DATA", "1");
        
        listeningThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                String line = in.nextLine();
                EyeData parsed = parseEyeData(line);
                listener.eyeData(parsed);
            }
        },"tracker-listening-thread");
    }
    
    public void stopData() {
        if(listeningThread == null) {
            logger.error("Attempt to stop data stream when it's not active");
            return;
        }
        
        logger.info("Stopping data stream from tracker server");
        set("ENABLE_SEND_DATA", "0");

        listeningThread.interrupt();
        listeningThread = null;
    }
    
    private EyeData parseEyeData(String line) {
        // TODO
        return null;
    }
    
    public interface TrackerListener {
        public void eyeData(EyeData data);
    }
    
    public static class EyeData {
        boolean bestValid;
        double bestX;
        double bestY;
    }
    
    /*
    public static void main(String[] args) {
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(),
                        true);
                Scanner in = new Scanner(socket.getInputStream())) {

            calibrate(out, in);

            startData(out, in);

            for (int i = 0; i < 1000; i++) {
                System.out.println(in.nextLine());
            }

            set(out, "ENABLE_SEND_DATA", "0");
            System.out.println(in.nextLine());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void startData(PrintWriter out, Scanner in) {
        set(out, "ENABLE_SEND_TIME", "1");
        System.out.println(in.nextLine());

        set(out, "ENABLE_SEND_POG_BEST", "1");
        System.out.println(in.nextLine());

        set(out, "ENABLE_SEND_POG_FIX", "1");
        System.out.println(in.nextLine());

        set(out, "ENABLE_SEND_DATA", "1");
        System.out.println(in.nextLine());
    }

    static void calibrate(PrintWriter out, Scanner in) throws IOException {
        waitUntilEyes(out, in);

        set(out, "CALIBRATE_SHOW", "1");
        set(out, "CALIBRATE_START", "1");

        do {
            String line = in.nextLine();
            System.out.println(line);

            if (line.contains("ID=\"CALIB_RESULT\""))
                break;
        } while (true);

        set(out, "CALIBRATE_START", "0");
        set(out, "CALIBRATE_SHOW", "0");
    }

    static void waitUntilEyes(PrintWriter out, Scanner in) throws IOException {
        // Show camera screen
        set(out, "TRACKER_DISPLAY", "1");
        System.out.println(in.nextLine());

        // Eyes
        set(out, "ENABLE_SEND_EYE_LEFT", "1");
        System.out.println(in.nextLine());
        set(out, "ENABLE_SEND_EYE_RIGHT", "1");
        System.out.println(in.nextLine());

        // Start
        set(out, "ENABLE_SEND_DATA", "1");
        System.out.println(in.nextLine());

        // Wait until both eyes (and pupils?) are noted valid for a continuous
        // 3 second span
        Pattern leye = Pattern.compile("LEYEV=\"(\\d+)\"");
        Pattern reye = Pattern.compile("REYEV=\"(\\d+)\"");

        long vstart = -1;

        do {
            String line = in.nextLine();
            System.out.println(line);

            Matcher lm = leye.matcher(line);
            Matcher rm = reye.matcher(line);

            lm.find();
            rm.find();

            String lv = lm.group(1);
            String rv = lm.group(1);
            System.out.printf("lv: %s; rv: %s\n", lv, rv);

            if ("1".equals(lv) && "1".equals(rv)) { // Valid?
                if (vstart < 0) // New validity period?
                    vstart = System.currentTimeMillis();
            } else {
                vstart = -1;
            }
        } while ((System.currentTimeMillis() - vstart) >= 3 * 1000);

        // Shut down data stream
        set(out, "ENABLE_SEND_DATA", "0");
        System.out.println(in.nextLine());
        set(out, "ENABLE_SEND_EYE_LEFT", "0");
        System.out.println(in.nextLine());
        set(out, "ENABLE_SEND_EYE_RIGHT", "0");
        System.out.println(in.nextLine());

        // Hide camera
        set(out, "TRACKER_DISPLAY", "0");
        System.out.println(in.nextLine());
    }

    static void set(PrintWriter out, String opt, String to) {
        out.print("<SET ID=\"" + opt + "\" STATE=\"" + to + "\" />\r\n");
        out.flush();
    }

    static String get(PrintWriter out, Scanner in, String opt)
            throws IOException {
        out.print("<GET ID=\"" + opt + "\" />\r\n");
        out.flush();

        return in.nextLine();
    }
    */
}
