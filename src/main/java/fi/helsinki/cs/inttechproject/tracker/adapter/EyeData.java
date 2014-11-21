package fi.helsinki.cs.inttechproject.tracker.adapter;

public class EyeData {
    boolean bestValid;
    double bestX;
    double bestY;
    
    boolean leftEyeOK;
    boolean rightEyeOK;
    
    @Override
    public String toString() {
        return "EyeData [bestValid=" + bestValid + ", bestX=" + bestX
                + ", bestY=" + bestY + ", leftEyeOK=" + leftEyeOK
                + ", rightEyeOK=" + rightEyeOK + "]";
    }
}