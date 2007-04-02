//
// OIBReader.java
//

/*
LOCI Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ Melissa Linkert, Curtis Rueden, Chris Allan,
Eric Kjellman and Brian Loranger.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Library General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Library General Public License for more details.

You should have received a copy of the GNU Library General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.awt.image.BufferedImage;
import java.io.*;
import java.text.*;
import java.util.*;
import loci.formats.*;

/**
 * OIBReader is the file format reader for Fluoview FV1000 OIB files.
 *
 * @author Melissa Linkert linkert at wisc.edu
 */
public class OIBReader extends FormatReader {

  // -- Constants --

  private static final String NO_POI_MSG =
    "Jakarta POI is required to read OIB files. Please " +
    "obtain poi-loci.jar from http://loci.wisc.edu/ome/formats.html";

  // -- Static fields --

  private static boolean noPOI = false;
  private static ReflectedUniverse r = createReflectedUniverse();

  private static ReflectedUniverse createReflectedUniverse() {
    r = null;
    try {
      r = new ReflectedUniverse();
      r.exec("import org.apache.poi.poifs.filesystem.POIFSFileSystem");
      r.exec("import org.apache.poi.poifs.filesystem.DirectoryEntry");
      r.exec("import org.apache.poi.poifs.filesystem.DocumentEntry");
      r.exec("import org.apache.poi.poifs.filesystem.DocumentInputStream");
      r.exec("import java.util.Iterator");
    }
    catch (ReflectException exc) {
      noPOI = true;
    }
    return r;
  }

  // -- Fields --

  /** Current file */
  private RandomAccessStream in;

  /** Number of images. */
  private Vector nImages;

  /** Image width. */
  private Vector width;

  /** Image height. */
  private Vector height;

  /** Number of channels. */
  private Vector nChannels;

  /** Number of timepoints. */
  private Vector tSize;

  /** Number of Z slices. */
  private Vector zSize;

  /** Number of bytes per pixel. */
  private Vector bpp;

  /** Thumbnail width. */
  private int thumbWidth;

  /** Thumbnail height. */
  private int thumbHeight;

  /** Hashtable containing the directory entry for each plane. */
  private Vector pixels;

  /**
   * Hashtable containing the document name for each plane,
   * indexed by the plane number.
   */
  private Vector names;

  /** Vector containing Z indices. */
  private Vector[] zIndices;

  /** Vector containing C indices. */
  private Vector[] cIndices;

  /** Vector containing T indices. */
  private Vector[] tIndices;

  /** Number of valid bits per pixel. */
  private int[][] validBits;

  private boolean[] littleEndian;
  private Vector rgb;

  // -- Constructor --

  /** Constructs a new OIB reader. */
  public OIBReader() { super("Fluoview FV1000 OIB", "oib"); }

  // -- FormatReader API methods --

  /** Checks if the given block is a valid header for an OIB file. */
  public boolean isThisType(byte[] block) {
    return (block[0] == 0xd0 && block[1] == 0xcf &&
      block[2] == 0x11 && block[3] == 0xe0);
  }

  /** Determines the number of images in the given OIB file. */
  public int getImageCount(String id) throws FormatException, IOException {
    if (!id.equals(currentId)) initFile(id);
    return ((Integer) nImages.get(getSeries(id))).intValue();
  }

  /** Checks if the images in the file are RGB. */
  public boolean isRGB(String id) throws FormatException, IOException {
    if (!id.equals(currentId)) initFile(id);
    return ((Boolean) rgb.get(getSeries(id))).booleanValue();
  }

  /** Return true if the data is in little-endian format. */
  public boolean isLittleEndian(String id) throws FormatException, IOException {
    if (!id.equals(currentId)) initFile(id);
    return false;
  }

  /** Returns whether or not the channels are interleaved. */
  public boolean isInterleaved(String id, int subC)
    throws FormatException, IOException
  {
    return false;
  }

  /* @see loci.formats.IFormatReader#getSeriesCount(String) */
  public int getSeriesCount(String id) throws FormatException, IOException {
    if (!id.equals(currentId)) initFile(id);
    return width.size();
  }

  /* @see loci.formats.IFormatReader#getChannelGlobalMinimum(String, int) */
  public Double getChannelGlobalMinimum(String id, int theC)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id);
    String s = (String) getMeta("[Image Parameters] - DataMin");
    try { return new Double(s); }
    catch (NumberFormatException exc) { return null; }
  }

  /* @see loci.formats.IFormatReader#getChannelGlobalMaximum(String, int) */
  public Double getChannelGlobalMaximum(String id, int theC)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id);
    String s = (String) getMeta("[Image Parameters] - DataMax");
    try { return new Double(s); }
    catch (NumberFormatException exc) { return null; }
  }

  /* @see loci.formats.IFormatReader#isMinMaxPopulated(String) */
  public boolean isMinMaxPopulated(String id)
    throws FormatException, IOException
  {
    return true;
  }

  /** Obtains the specified image from the given ZVI file, as a byte array. */
  public byte[] openBytes(String id, int no)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id);
    byte[] buf = new byte[sizeX[series] * sizeY[series] *
      getRGBChannelCount(id) *
      FormatTools.getBytesPerPixel(pixelType[series])];
    return openBytes(id, no, buf);
  }

  public byte[] openBytes(String id, int no, byte[] buf)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id);
    if (no < 0 || no >= getImageCount(id)) {
      throw new FormatException("Invalid image number: " + no);
    }

    try {
      Integer ii = new Integer(no);
      String directory = (String) ((Hashtable) pixels.get(series)).get(ii);
      String name = (String) ((Hashtable) names.get(series)).get(ii);

      r.setVar("dirName", directory);
      r.exec("root = fs.getRoot()");
      r.exec("dir = root.getEntry(dirName)");
      r.setVar("entryName", name);
      r.exec("document = dir.getEntry(entryName)");
      r.exec("dis = new DocumentInputStream(document)");
      r.exec("numBytes = dis.available()");
      int numBytes = ((Integer) r.getVar("numBytes")).intValue();
      byte[] b = new byte[numBytes + 4]; // append 0 for final offset
      r.setVar("data", b);
      r.exec("dis.read(data)");

      RandomAccessStream stream = new RandomAccessStream(b);
      Hashtable[] ifds = TiffTools.getIFDs(stream);
      littleEndian[series] = TiffTools.isLittleEndian(ifds[0]);
      TiffTools.getSamples(ifds[0], stream, ignoreColorTable, buf);
      stream.close();
      updateMinMax(buf, no);
      return buf;
    }
    catch (ReflectException e) {
      throw new FormatException(e);
    }
  }

  /** Obtains the specified image from the given ZVI file. */
  public BufferedImage openImage(String id, int no)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id);
    if (no < 0 || no >= getImageCount(id)) {
      throw new FormatException("Invalid image number: " + no);
    }

    byte[] b = openBytes(id, no);
    int bytes = b.length / (sizeX[series] * sizeY[series] *
      getRGBChannelCount(id));

    BufferedImage bi = ImageTools.makeImage(b, sizeX[series], sizeY[series],
      getRGBChannelCount(id), false, bytes, !littleEndian[series],
      validBits[series]);
    updateMinMax(bi, no);
    return bi;
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws FormatException, IOException {
    if (fileOnly && in != null) in.close();
    else if (!fileOnly) close();
  }

  /** Closes any open files. */
  public void close() throws FormatException, IOException {
    if (in != null) in.close();
    in = null;
    currentId = null;

    String[] vars = {"dirName", "root", "dir", "document", "dis",
      "numBytes", "data", "fis", "fs", "iter", "isInstance", "isDocument",
      "entry", "documentName", "entryName"};
    for (int i=0; i<vars.length; i++) r.setVar(vars[i], null);
  }

  /** Initializes the given OIB file. */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("OIBReader.initFile(" + id + ")");
    if (noPOI) throw new FormatException(NO_POI_MSG);
    super.initFile(id);

    zIndices = new Vector[] {new Vector()};
    cIndices = new Vector[] {new Vector()};
    tIndices = new Vector[] {new Vector()};

    width = new Vector();
    height = new Vector();
    nChannels = new Vector();
    zSize = new Vector();
    tSize = new Vector();
    pixels = new Vector();
    names = new Vector();
    nImages = new Vector();
    bpp = new Vector();
    bpp.add(new Integer(0));
    rgb = new Vector();

    try {
      in = new RandomAccessStream(id);
      if (in.length() % 4096 != 0) {
        in.setExtend(4096 - (int) (in.length() % 4096));
      }
      r.setVar("fis", in);
      r.exec("fs = new POIFSFileSystem(fis)");
      r.exec("dir = fs.getRoot()");
      parseDir(0, r.getVar("dir"));

      int numSeries = width.size();

      status("Sorting images");

      // sort names

      for (int i=0; i<numSeries; i++) {
        Vector newKeys = new Vector();
        Vector comps = new Vector();
        Enumeration keys = ((Hashtable) names.get(i)).keys();
        while (keys.hasMoreElements()) {
          Object key = keys.nextElement();
          String value = (String) ((Hashtable) names.get(i)).get(key);
          int comp = Integer.parseInt(value.substring(value.indexOf("0")));
          int size = newKeys.size();
          for (int j=0; j<size; j++) {
            if (comp < ((Integer) comps.get(j)).intValue()) {
              newKeys.add(j, value);
              comps.add(j, new Integer(comp));
              j = size;
            }
            else if (j == size - 1) {
              newKeys.add(value);
              comps.add(new Integer(comp));
              j = size;
            }
          }
          if (newKeys.size() == 0) {
            newKeys.add(value);
            comps.add(new Integer(comp));
          }
        }

        Hashtable newNames = new Hashtable();
        for (int j=0; j<newKeys.size(); j++) {
          newNames.put(new Integer(j), newKeys.get(j));
        }
        names.setElementAt(newNames, i);
      }

      status("Populating metadata");

      String[] labels = new String[9];
      String[] dims = new String[9];
      String[] starts = new String[9];
      String[] stops = new String[9];

      for (int i=0; i<labels.length; i++) {
        String pre = "[Axis " + i + " Parameters Common] - ";
        labels[i] = (String) getMeta(pre + "AxisCode");
        dims[i] = (String) getMeta(pre + "MaxSize");
        starts[i] = (String) getMeta(pre + "StartPosition");
        stops[i] = (String) getMeta(pre + "EndPosition");
        if (labels[i] == null) labels[i] = "";
        if (dims[i] == null) dims[i] = "0";
        if (starts[i] == null) starts[i] = "0";
        if (stops[i] == null) stops[i] = "0";
      }

      for (int i=0; i<labels.length; i++) {
        if (labels[i].equals("\"X\"") || labels[i].equals("\"Y\"")) { }
        else if (labels[i].equals("\"C\"")) {
          if (!starts[i].equals(stops[i])) nChannels.add(new Integer(dims[i]));
          else nChannels.add(new Integer(1));
        }
        else if (labels[i].equals("\"Z\"")) {
          if (!starts[i].equals(stops[i])) zSize.add(new Integer(dims[i]));
          else zSize.add(new Integer(1));
        }
        else if (labels[i].equals("\"T\"")) {
          if (!starts[i].equals(stops[i])) tSize.add(new Integer(dims[i]));
          else tSize.add(new Integer(1));
        }
        else if (!dims[i].equals("0")) {
          if (nChannels.size() > 0) {
            int ch = ((Integer) nChannels.get(nChannels.size() - 1)).intValue();
            ch *= Integer.parseInt(dims[i]);
            nChannels.setElementAt(new Integer(ch), nChannels.size() - 1);
          }
          else nChannels.add(new Integer(dims[i]));
        }
      }

      pixelType = new int[numSeries];
      currentOrder = new String[numSeries];
      orderCertain = new boolean[numSeries];
      littleEndian = new boolean[numSeries];

      sizeX = new int[numSeries];
      sizeY = new int[numSeries];
      sizeZ = new int[numSeries];
      sizeC = new int[numSeries];
      sizeT = new int[numSeries];
      validBits = new int[numSeries][];
      cTypes = new String[numSeries][];
      cLengths = new int[numSeries][]; 

      for (int i=0; i<numSeries; i++) {
        sizeX[i] = ((Integer) width.get(i)).intValue();
        sizeY[i] = ((Integer) height.get(i)).intValue();

        if (i < zSize.size()) sizeZ[i] = ((Integer) zSize.get(i)).intValue();
        else sizeZ[i] = 1;

        if (i < nChannels.size()) {
          sizeC[i] = ((Integer) nChannels.get(i)).intValue();
        }
        else sizeC[i] = 1;

        if (i < tSize.size()) sizeT[i] = ((Integer) tSize.get(i)).intValue();
        else sizeT[i] = 1;

        if (sizeZ[i] == 0) sizeZ[i]++;
        if (sizeT[i] == 0) sizeT[i]++;

        currentOrder[i] = (sizeZ[i] > sizeT[i]) ? "XYCZT" : "XYCTZ";

        int numImages = ((Integer) nImages.get(i)).intValue();

        if (numImages > sizeZ[i] * sizeT[i] * sizeC[i]) {
          int diff = numImages - (sizeZ[i] * sizeT[i] * sizeC[i]);

          if (diff % sizeZ[i] == 0 && sizeZ[i] > 1) {
            while (numImages > sizeZ[i] * sizeT[i] * sizeC[i]) sizeT[i]++;
          }
          else if (diff % sizeT[i] == 0 && sizeT[i] > 1) {
            while (numImages > sizeZ[i] * sizeT[i] * sizeC[i]) sizeZ[i]++;
          }
          else if (diff % sizeC[i] == 0) {
            if (sizeZ[i] > sizeT[i]) {
              while (numImages > sizeZ[i] * sizeC[i] * sizeT[i]) sizeZ[i]++;
            }
            else {
              while (numImages > sizeZ[i] * sizeC[i] * sizeT[i]) sizeT[i]++;
            }
          }
        }

        int oldSeries = getSeries(id);
        setSeries(id, i);
        while (numImages < sizeZ[i] * sizeT[i] * getEffectiveSizeC(id)) {
          numImages++;
        }
        nImages.setElementAt(new Integer(numImages), i);
        setSeries(id, oldSeries);

        validBits[i] = new int[sizeC[i] == 2 ? 3 : sizeC[i]];
        int vb = 0;
        Enumeration k = metadata.keys();
        while (k.hasMoreElements()) {
          String key = k.nextElement().toString();
          if (key.indexOf("ValidBitCounts") != -1) {
            vb = Integer.parseInt((String) getMeta(key));
          }
        }
        if (vb > 0) {
          for (int j=0; j<validBits[i].length; j++) validBits[i][j] = vb;
        }
        else validBits[i] = null;
      }
    }
    catch (ReflectException e) {
      throw new FormatException(e);
    }

    try {
      initMetadata();
    }
    catch (FormatException e) {
      if (debug) e.printStackTrace();
    }
    catch (IOException e) {
      if (debug) e.printStackTrace();
    }
  }

  // -- Helper methods --

  /** Initialize metadata hashtable and OME-XML structure. */
  private void initMetadata() throws FormatException, IOException {
    MetadataStore store = getMetadataStore(currentId);
    store.setImage((String) getMeta("DataName"), null, null, null);

    for (int i=0; i<width.size(); i++) {
      switch (((Integer) bpp.get(0)).intValue() % 3) {
        case 0:
        case 1:
          pixelType[i] = FormatTools.UINT8;
          break;
        case 2:
          pixelType[i] = FormatTools.UINT16;
          break;
        case 4:
          pixelType[i] = FormatTools.UINT32;
          break;
        default:
          throw new RuntimeException(
            "Unknown matching for pixel byte width of: " + bpp);
      }

      String acquisition = "[Acquisition Parameters Common] - ";
      
      String stamp = (String) getMeta(acquisition + "ImageCaputreDate"); 
    
      if (stamp != null) {
        stamp = stamp.substring(1, stamp.length() - 1); 
        SimpleDateFormat parse = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date = parse.parse(stamp, new ParsePosition(0));
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        stamp = fmt.format(date);
      } 

      store.setImage(null, stamp, null, null);

      store.setPixels(
        new Integer(sizeX[i]),
        new Integer(sizeY[i]),
        new Integer(sizeZ[i]),
        new Integer(sizeC[i]),
        new Integer(sizeT[i]),
        new Integer(pixelType[i]),
        new Boolean(false),
        currentOrder[i],
        new Integer(i),
        null);

      Float pixX = new Float(getMeta(
        "[Reference Image Parameter] - WidthConvertValue").toString());
      Float pixY = new Float(getMeta(
        "[Reference Image Parameter] - HeightConvertValue").toString());
      store.setDimensions(pixX, pixY, null, null, null, new Integer(i));
      for (int j=0; j<sizeC[0]; j++) {
        store.setLogicalChannel(j, null, null, null, null, null,
          null, new Integer(i));

        String prefix = "[Channel " + (j + 1) + " Parameters] - ";
        String gain = (String) getMeta(prefix + "AnalogPMTGain");
        String offset = (String) getMeta(prefix + "AnalogPMTOffset");
        String voltage = (String) getMeta(prefix + "AnalogPMTVoltage");

        store.setDetector(null, null, null, null,
          gain == null ? null : new Float(gain),
          voltage == null ? null : new Float(voltage),
          offset == null ? null : new Float(offset), null, new Integer(j));
      }

      String laserCount = (String) getMeta(acquisition + "Number of use Laser");
      int numLasers = laserCount == null ? 0 : Integer.parseInt(laserCount);

      for (int j=0; j<numLasers; j++) {
        String wave =
          (String) getMeta(acquisition + "LaserWavelength0" + (j + 1));
        if (wave == null) wave = "0";
        store.setLaser(null, null, new Integer(wave), null, null, null, null,
          null, null, null, new Integer(j));
      }

    }
  }

  protected void parseDir(int depth, Object dir)
    throws IOException, FormatException, ReflectException
  {
    r.setVar("dir", dir);
    r.exec("dirName = dir.getName()");
    r.setVar("depth", depth);
    r.exec("iter = dir.getEntries()");
    Iterator iter = (Iterator) r.getVar("iter");
    while (iter.hasNext()) {
      r.setVar("entry", iter.next());
      r.exec("isInstance = entry.isDirectoryEntry()");
      r.exec("isDocument = entry.isDocumentEntry()");
      boolean isInstance = ((Boolean) r.getVar("isInstance")).booleanValue();
      boolean isDocument = ((Boolean) r.getVar("isDocument")).booleanValue();
      r.setVar("dir", dir);
      r.exec("dirName = dir.getName()");
      if (isInstance)  {
        status("Parsing embedded folder (" + (depth + 1) + ")"); 
        parseDir(depth + 1, r.getVar("entry"));
      }
      else if (isDocument) {
        status("Parsing embedded file (" + depth + ")");
        r.exec("entryName = entry.getName()");
        if (debug) {
          print(depth + 1, "Found document: " + r.getVar("entryName"));
        }
        r.exec("dis = new DocumentInputStream(entry)");
        r.exec("numBytes = dis.available()");
        int numbytes = ((Integer) r.getVar("numBytes")).intValue();
        byte[] data = new byte[numbytes + 4]; // append 0 for final offset
        r.setVar("data", data);
        r.exec("dis.read(data)");

        String entryName = (String) r.getVar("entryName");
        String dirName = (String) r.getVar("dirName");

        boolean isContents = entryName.toUpperCase().equals("CONTENTS");
        Object directory = r.getVar("dir");

        int pt = 0;

        // check the first 2 bytes of the stream

        byte[] b = {data[0], data[1], data[2], data[3]};

        if (data[0] == 0x42 && data[1] == 0x4d) {
          // this is the thumbnail
        }
        else if (TiffTools.checkHeader(b) != null) {
          // this is an actual image plane

          RandomAccessStream ras = new RandomAccessStream(data);
          Hashtable ifd = TiffTools.getIFDs(ras)[0];
          ras.close();
          int w = (int) TiffTools.getImageWidth(ifd);
          int h = (int) TiffTools.getImageLength(ifd);

          boolean isRGB = TiffTools.getSamplesPerPixel(ifd) > 1;
          if (!isRGB) {
            int p = TiffTools.getPhotometricInterpretation(ifd);
            isRGB = !isColorTableIgnored() && (p == TiffTools.RGB_PALETTE ||
              p == TiffTools.CFA_ARRAY) || p == TiffTools.RGB;
          }

          boolean added = false;
          for (int i=0; i<width.size(); i++) {
            if (((Integer) width.get(i)).intValue() == w &&
              ((Integer) height.get(i)).intValue() == h)
            {
              int num = ((Integer) nImages.get(i)).intValue();
              ((Hashtable) pixels.get(i)).put(new Integer(num), dirName);
              ((Hashtable) names.get(i)).put(new Integer(num), entryName);
              num++;
              nImages.setElementAt(new Integer(num), i);
              added = true;
            }
          }

          if (!added) {
            Hashtable ht = new Hashtable();
            ht.put(new Integer(0), dirName);
            pixels.add(ht);
            ht = new Hashtable();
            ht.put(new Integer(0), entryName);
            names.add(ht);
            nImages.add(new Integer(1));
            width.add(new Integer(w));
            height.add(new Integer(h));
            rgb.add(new Boolean(isRGB));
          }
        }
        else if (entryName.equals("OibInfo.txt")) { /* ignore this */ }
        else if (data[0] == (byte) 0xff && data[1] == (byte) 0xfe) {
          String ini = DataTools.stripString(new String(data));
          StringTokenizer st = new StringTokenizer(ini, "\n");
          String prefix = "";
          while (st.hasMoreTokens()) {
            String line = st.nextToken().trim();
            if (!line.startsWith("[") && (line.indexOf("=") > 0)) {
              String key = line.substring(0, line.indexOf("=")).trim();
              String value = line.substring(line.indexOf("=") + 1).trim();

              if (prefix.equals("[FileInformation] - ") &&
                key.equals("Resolution"))
              {
                int max = Integer.parseInt(value);
                int bytes = ((Integer) bpp.get(0)).intValue();
                while (Math.pow(2, bytes) < max) bytes++;
                bytes /= 8;
                for (int i=0; i<bpp.size(); i++) {
                  bpp.setElementAt(new Integer(bytes), i);
                }
              }

              if (prefix.indexOf("Red") == -1 &&
                prefix.indexOf("Green") == -1 && prefix.indexOf("Blue") == -1)
              {
                addMeta(prefix + key, value);
              }
            }
            else {
              if (line.indexOf("[") == 2) {
                line = line.substring(2, line.length());
              }
              prefix = line + " - ";
            }
          }
          data = null;
        }

        r.exec("dis.close()");
      }
    }
  }

  /** Debugging helper method. */
  protected void print(int depth, String s) {
    StringBuffer sb = new StringBuffer();
    for (int i=0; i<depth; i++) sb.append("  ");
    sb.append(s);
    debug(sb.toString());
  }

  // -- Main method --
  public static void main(String[] args) throws FormatException, IOException {
    new OIBReader().testRead(args);
  }
}
