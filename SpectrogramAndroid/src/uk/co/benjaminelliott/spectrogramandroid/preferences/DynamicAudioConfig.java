package uk.co.benjaminelliott.spectrogramandroid.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class DynamicAudioConfig {
    
    public static final String PREF_COLOURMAP_KEY = "pref_colourmap";
    public static final String PREF_CONTRAST_KEY = "pref_contrast";
    public static final String PREF_SAMPLE_RATE_KEY = "pref_sample_rate";
    public static final String PREF_SAMPLES_WINDOW_KEY = "pref_samples_window";
    public static final String PREF_OVERFILTER_KEY = "pref_overfilter";
    public static final String PREF_AUDIO_KEY = "pref_user_test_audio";
    
    public static final String STORE_DIR_NAME = "Spectrogram captures";
    
    //number of windows that can be held in the arrays at once before older ones are deleted. Time this represents is
    // WINDOW_LIMIT*SAMPLES_PER_WINDOW/SAMPLE_RATE, e.g. 10000*300/16000 = 187.5 seconds.
    public static final int WINDOW_LIMIT = 1000; //usually around 10000 
    
    public static final int BITMAP_STORE_WIDTH_ADJ = 2;
    public static final int BITMAP_STORE_HEIGHT_ADJ = 2;
    public static final int BITMAP_STORE_QUALITY = 90; //compression quality parameter for storage
    public static final int BITMAP_FREQ_AXIS_WIDTH = 30; //number of pixels (subject to width adjustment) to use to display frequency axis on stored bitmaps

    
    public final int SAMPLE_RATE; //options are 11025, 16000, 22050, 44100
    public final int SAMPLES_PER_WINDOW; //usually around 300
    public final int NUM_FREQ_BINS;    

    public final float CONTRAST;
    public final int COLOUR_MAP;
    public final boolean OVERFILTER;

    
    public DynamicAudioConfig(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SAMPLE_RATE = Integer.parseInt(prefs.getString(PREF_SAMPLE_RATE_KEY, "16000"));
        SAMPLES_PER_WINDOW = Integer.parseInt(prefs.getString(PREF_SAMPLES_WINDOW_KEY, "300"));
        OVERFILTER = prefs.getBoolean(PREF_OVERFILTER_KEY, false);
        NUM_FREQ_BINS = SAMPLES_PER_WINDOW / 2; //lose half because of symmetry
        
        String colMapString = prefs.getString(PREF_COLOURMAP_KEY, "NULL");
        if (!colMapString.equals("NULL")) {
            COLOUR_MAP = Integer.parseInt(prefs.getString(PREF_COLOURMAP_KEY, "NULL"));
        }
        else {
            COLOUR_MAP = 0;
        }
        
        float newContrast = prefs.getFloat(PREF_CONTRAST_KEY, Float.MAX_VALUE);
        if (newContrast != Float.MAX_VALUE) {
            // slider value must be between 0 and 1, so multiply by 3 and add 1 to
            // get something more 'in range' of the usual contrast value
            CONTRAST = newContrast * 3.0f + 1.0f; 
        }
        else {
            CONTRAST = newContrast;
        }
   

    }

}
