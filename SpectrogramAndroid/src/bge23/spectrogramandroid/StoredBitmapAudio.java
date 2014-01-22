package bge23.spectrogramandroid;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import android.graphics.Bitmap;
import android.os.Environment;
import android.text.format.Time;
import android.util.Log;

public class StoredBitmapAudio {
	
	private String filename;
	private String directory;
	private short[] audioData;
	private Bitmap bitmap;
	private int sampleRate;
	
	protected StoredBitmapAudio(String directory, Bitmap bitmap, short[] audioData) {
		this.directory = directory;
		this.bitmap = bitmap;
		this.audioData = audioData;
		sampleRate = BitmapGenerator.SAMPLE_RATE;
		Time time = new Time();
		time.setToNow();
		filename = "Spectrogram-"+time.format("%F")+"-"+time.format("%T");
	}
	
	protected void store() {
		writeBitmapToJPEG();
		writeAudioToWAV();
	}
	
	private void writeBitmapToJPEG() {
		if (isExternalStorageWritable()) {
			File dir = getAlbumStorageDir(directory);
			FileOutputStream fos = null;
			try {
				File bmpFile = new File(dir.getAbsolutePath()+"/"+filename+".jpg");
				fos = new FileOutputStream(bmpFile);
				bitmap.compress(Bitmap.CompressFormat.JPEG, BitmapGenerator.BITMAP_STORE_QUALITY, fos);
				Log.d("","Bitmap stored successfully at path "+bmpFile.getAbsolutePath());
			} catch (FileNotFoundException e) {
				Log.d("","File not found. Path: "+dir.getAbsolutePath()+"/"+filename);
				e.printStackTrace();
			} finally {
				try {
					fos.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	private void writeAudioToWAV() {
		byte[] header = getWAVHeader(sampleRate,16,audioData.length*2,1);
		FileOutputStream fos = null;
		DataOutputStream dos = null;
		if (isExternalStorageWritable()) {
			File dir = getAlbumStorageDir(directory);
			try {
				File audioFile = new File(dir.getAbsolutePath()+"/"+filename+".wav");
				fos = new FileOutputStream(audioFile);
				fos.write(header, 0, 44);
				dos = new DataOutputStream(fos);
				for (int i = 0; i < audioData.length; i++) {
					dos.writeShort(audioData[i]);
				}
				Log.d("","Audio file stored successfully at path "+audioFile.getAbsolutePath());
			} catch (FileNotFoundException e) {
				Log.d("","File not found. Path: "+dir.getAbsolutePath()+"/"+filename);
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {
					dos.close();
					fos.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	
	private byte[] getWAVHeader(int sampleRate, int bitsPerSample, int audioDataLength, int numChannels) {
		byte[] header = new byte[44];
		//NOTE all fields are little-endian unless they contain characters
		//See https://ccrma.stanford.edu/courses/422/projects/WaveFormat/
		
		/* RIFF HEADER */
		//ChunkID:
        header[0] = 'R';  
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        //ChunkSide = size of entire file - 8 bytes for ChunkID and ChunkSize
		int totalLength = audioDataLength + 36; //44 bytes for header - 8 bytes
        header[4] = (byte) (totalLength & 0xff); //mask all but LSB
        header[5] = (byte) ((totalLength >> 8) & 0xff); //shift right and mask all but LSB (now 2nd LSB)
        header[6] = (byte) ((totalLength >> 16) & 0xff); //etc
        header[7] = (byte) ((totalLength >> 24) & 0xff);
        //Format:
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        
        /* "fmt " (note space) SUBCHUNK */
        //SubChunk1ID:
        header[12] = 'f';  
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        //SubChunk1Size:
        header[16] = 16;  //16 for PCM (remember little-endian)
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        //AudioFormat:
        header[20] = 1;  //1 for PCM (linear quantization; no compression) 
        header[21] = 0;
        //NumChannels:
        header[22] = (byte) numChannels;
        header[23] = 0;
        //SampleRate:
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        //ByteRate = SampleRate*NumChannels*BitsPerSample/8:
		int byteRate = numChannels * sampleRate * bitsPerSample / 8;
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        //BlockAlign = NumChannels*BitsPerSample/8;
        header[32] = (byte) (2 * 16 / 8); 
        header[33] = 0;
        //BitsPerSample:
        header[34] = (byte) bitsPerSample;  
        header[35] = 0;
        
        /* "data" SUBCHUNK */
        //SubChunk2ID:
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        //SubChunk2Size:
        header[40] = (byte) (audioDataLength & 0xff);
        header[41] = (byte) ((audioDataLength >> 8) & 0xff);
        header[42] = (byte) ((audioDataLength >> 16) & 0xff);
        header[43] = (byte) ((audioDataLength >> 24) & 0xff);
        //actual data will go here
		return header;
	}

	/* FROM ANDROID DEV DOCUMENTATION */
	public boolean isExternalStorageWritable() {
	    String state = Environment.getExternalStorageState();
	    if (Environment.MEDIA_MOUNTED.equals(state)) {
	        return true;
	    }
	    return false;
	}
	
	public File getAlbumStorageDir(String albumName) {
	    // Get the directory for the user's public pictures directory.
	    File file = new File(Environment.getExternalStoragePublicDirectory(
	            Environment.DIRECTORY_PICTURES), albumName);
	    if (!file.mkdirs()) {
	        Log.e("", "Directory not created");
	    }
	    return file;
	}
	/* 		*/

}
