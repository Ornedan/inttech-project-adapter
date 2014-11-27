package fi.helsinki.cs.inttechproject.tracker.adapter;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TrackerConnection {

    private static final String intGroup = "\"(\\d+)\"";
    private static final String decGroup = "\"([-]?\\d+\\.\\d+)\"";
    
    // Response extraction regexes
    private static final Pattern leyevP = Pattern.compile("LEYEV=" + intGroup);
    private static final Pattern reyevP = Pattern.compile("REYEV=" + intGroup);
    
    private static final Pattern aveerrorP = Pattern.compile("AVE_ERROR=" + decGroup);
    private static final Pattern validpointsP = Pattern.compile("VALID_POINTS=" + intGroup);
    
    private static final Pattern bpogvP = Pattern.compile("BPOGV=" + intGroup);
    private static final Pattern bpogxP = Pattern.compile("BPOGX=" + decGroup);
    private static final Pattern bpogyP = Pattern.compile("BPOGY=" + decGroup);
    
    
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
    private Runnable stopListeningThread;
    
    
    public TrackerConnection() {
        logger = LogManager.getLogger(TrackerConnection.class);
    }

    public synchronized void connect() {
        if(socket != null) {
            logger.warn("Request to connect to the tracker server made when already connected");
            return; // Make sure we only have one connection at a time
        }   
        
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
    
    public synchronized void disconnect() {
        if(socket == null)
            logger.warn("Request to disconnect from the tracker server when not connected");
        
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
        if(this.listeningThread != null)
            stopData();
        
        boolean acceptableCalibration;
        do {
            synchronized (this) {
                logger.info("Starting calibration process");
                waitUntilEyes();

                set("CALIBRATE_SHOW", "1");
                set("CALIBRATE_START", "1");

                do {
                    String line = nextLine();
                    // System.out.println(line);

                    if (line.contains("ID=\"CALIB_RESULT\""))
                        break;
                } while (true);

                // Show summary
                String summary = get("CALIBRATE_RESULT_SUMMARY");
                logger.info("Calibration result: {}", summary);

                Matcher avem = aveerrorP.matcher(summary);
                Matcher validm = validpointsP.matcher(summary);

                avem.find();
                validm.find();

                double aveErr = Double.parseDouble(avem.group(1));
                int validPoints = Integer.parseInt(validm.group(1));
                
                if(validPoints < 9) {
                    logger.info("Some calibration points were invalid, rerunning");
                    acceptableCalibration = false;
                }
                else if(aveErr >= 40d) {
                    logger.info("Calibration error was too high, rerunning");
                    acceptableCalibration = false;
                }
                else
                    acceptableCalibration = true;

                set("CALIBRATE_START", "0");
                set("CALIBRATE_SHOW", "0");
            }
        } while (!acceptableCalibration);
    }

    public synchronized void startData(TrackerListener listener) {
        if(listeningThread != null) {
            logger.warn("Attempt to start data stream when it's already active");
            return;
        }
        
        logger.info("Requesting data stream from tracker server");
        //set("ENABLE_SEND_TIME", "1", false); // Every other bloody SET is ack'd besides this one
        set("ENABLE_SEND_POG_BEST", "1");
        //set("ENABLE_SEND_POG_FIX", "1");
        set("ENABLE_SEND_EYE_LEFT", "1");
        set("ENABLE_SEND_EYE_RIGHT", "1");
        set("ENABLE_SEND_DATA", "1");
        
        AtomicBoolean end = new AtomicBoolean(false);
        listeningThread = new Thread(() -> {
            while (!Thread.interrupted() && !end.get()) {
                String line = nextLine();
                EyeData parsed = parseEyeData(line);
                
                if(parsed != null)
                    listener.eyeData(parsed);
            }
        },"tracker-listening-thread");
        
        stopListeningThread = () -> end.set(true);
        
        listeningThread.setDaemon(true);
        listeningThread.start();
    }
    
    public void stopData() {
        synchronized (this) {
            if (listeningThread == null) {
                logger.warn("Attempt to stop data stream when it's not active");
                return;
            }

            logger.info("Stopping data stream from tracker server");

            stopListeningThread.run();
            //listeningThread.interrupt();
        }

        try {
            listeningThread.join();
        } catch (InterruptedException e) {
            return;
        }

        synchronized (this) {
            set("ENABLE_SEND_DATA", "0");
            set("ENABLE_SEND_POG_BEST", "0");
            set("ENABLE_SEND_EYE_LEFT", "0");
            set("ENABLE_SEND_EYE_RIGHT", "0");

            stopListeningThread = null;
            listeningThread = null;
        }
    }

    private void waitUntilEyes() {
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
        Long vlstart = null;
        Long vrstart = null;

        do {
            String line = nextLine();
            //System.out.println(line);

            Matcher lm = leyevP.matcher(line);
            Matcher rm = reyevP.matcher(line);

            lm.find();
            rm.find();

            String lv = lm.group(1);
            String rv = lm.group(1);
            //System.out.printf("lv: %s; rv: %s\n", lv, rv);

            if("1".equals(lv)) {
                if(vlstart == null) {
                    vlstart = System.currentTimeMillis();
                    logger.debug("Tracker identified left eye");
                }
            }
            else {
                if(vlstart != null)
                    logger.debug("Tracker lost left eye after {} msec",(System.currentTimeMillis() - vlstart));
                vlstart = null;
            }
            
            if("1".equals(rv)) {
                if(vrstart == null) {
                    vrstart = System.currentTimeMillis();
                    logger.debug("Tracker identified right eye");
                }
            }
            else {
                if(vrstart != null)
                    logger.debug("Tracker lost right eye after {} msec",(System.currentTimeMillis() - vrstart));
                vrstart = null;
            }
            
            if(vlstart != null && vrstart != null)
                logger.debug("Tracker has both eyes");
        } while ((vlstart == null || (System.currentTimeMillis() - vlstart) <= 200)
              && (vrstart == null || (System.currentTimeMillis() - vrstart) <= 200));
        
        logger.info("Tracker has seen both eyes for {} msec, OK", System.currentTimeMillis() - Math.max(vlstart, vrstart));

        // Shut down data stream
        set("ENABLE_SEND_DATA", "0");
        set("ENABLE_SEND_EYE_LEFT", "0");
        set("ENABLE_SEND_EYE_RIGHT", "0");

        // Hide camera
        set("TRACKER_DISPLAY", "0");
    }

    private void set(String opt, String to) {
        set(opt, to, true);
    }
    
    private synchronized void set(String opt, String to, boolean hasAck) {
        out.print("<SET ID=\"" + opt + "\" STATE=\"" + to + "\" />\r\n");
        out.flush();
        
        if(hasAck) {
            Pattern pat = Pattern.compile("<ACK ID=\"" + opt + "\"");
            
            while(true)
            {
                String line = nextLine();
                Matcher match = pat.matcher(line);
                
                if(match.find())
                {
                    logger.debug("Set {} to {}, got response {}", opt, to, line);
                    break;
                }
                else
                    logger.debug(
                            "Skipped line while waiting for ack for SET {}={}: {}",
                            opt, to, line);
            }
        }
    }

    private synchronized String get(String opt) {
        out.print("<GET ID=\"" + opt + "\" />\r\n");
        out.flush();

        return nextLine();
    }
    
    private synchronized String nextLine() {
        return in.nextLine();
    }

    private EyeData parseEyeData(String line) {
        // System.out.println(line);

        Matcher vm = bpogvP.matcher(line);
        Matcher xm = bpogxP.matcher(line);
        Matcher ym = bpogyP.matcher(line);
        Matcher lm = leyevP.matcher(line);
        Matcher rm = reyevP.matcher(line);

        // Is this even an eye data line?
        if(!vm.find())
            return null;
        
        xm.find();
        ym.find();
        lm.find();
        rm.find();

        String v = vm.group(1);
        String x = xm.group(1);
        String y = ym.group(1);
        String lv = lm.group(1);
        String rv = lm.group(1);
        
        EyeData data = new EyeData();
        data.bestValid = "1".equals(v);
        data.bestX = Double.valueOf(x);
        data.bestY = Double.valueOf(y);
        data.leftEyeOK = "1".equals(lv);
        data.rightEyeOK = "1".equals(rv);
        
        logger.debug("Got eye data: {}", data);
        
        return data;
    }
    
    public interface TrackerListener {
        public void eyeData(EyeData data);
    }
}
