package moe.lottev.droidkiss;

// Special thanks to William Miles for his LZH archive logic.
// It would've been a headache to implement without the existence of UltraKiSS.
// You can find the full UltraKiSS source code here:
// https://github.com/kisekae/ultrakiss

// ----------------------------------------------------------------------------

// Title:        Kisekae UltraKiss
// Version:      3.4  (May 11, 2023)
// Copyright:    Copyright (c) 2002-2023
// Author:       William Miles
// Description:  Kisekae Set System
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA

/*
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
%  This copyright notice and this permission notice shall be included in      %
%  all copies or substantial portions of UltraKiss.                           %
%                                                                             %
%  The software is provided "as is", without warranty of any kind, express or %
%  implied, including but not limited to the warranties of merchantability,   %
%  fitness for a particular purpose and noninfringement.  In no event shall   %
%  William Miles be liable for any claim, damages or other liability,         %
%  whether in an action of contract, tort or otherwise, arising from, out of  %
%  or in connection with Kisekae UltraKiss or the use of UltraKiss.           %
%                                                                             %
%  William Miles                                                              %
%  144 Oakmount Rd. S.W.                                                      %
%  Calgary, Alberta                                                           %
%  Canada  T2V 4X4                                                            %
%                                                                             %
%  w.miles@wmiles.com                                                         %
%                                                                             %
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
*/


/**
 * LhaFile class
 * <p>
 * Purpose:
 * <p>
 * This class manages an LHA compressed file.  It contains methods
 * to open and close the file and to return an enumeration of the
 * entries contained in the file.
 *
 */

import android.util.Log;

import java.io.*;
import java.util.Vector;
import java.util.zip.ZipException;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class LhaFile extends ArchiveFile {
    private RandomAccessFile file = null;        // LHA random access file object


    // Constructor.  This method opens an LHA random access file for input
    // and constructs a vector of LHA elements where each element represents
    // a compressed file.
    /* Adjusted for Android. */
    public LhaFile(String name) throws IOException {
        this(name, "r");
    }


    // Constructor.  This method opens a new zip file from a memory file.
//   public LhaFile(String name, MemFile mem) throws IOException {
//       memfile = mem;
//       pathname = name;
//       contents = new Vector();
//       open();
//   }

    // Primary Android Constructor
    public LhaFile(String name, MemFile mem) throws IOException {
        this.pathname = name;
        this.memfile = mem;
        this.contents = new Vector();
        // We don't call readEntries(), we call open()
        // because open() has the scanning loop logic.
        open();
    }


    // Constructor.  This method opens a new LHA file in the specified mode.

    public LhaFile(String name, String mode) throws IOException {
        pathname = name;
        init();
        if ("rw".equals(mode)) file = new RandomAccessFile(pathname, mode);
        else open();
    }


    // Return the random access file.

    RandomAccessFile getRandomAccessFile() {
        return file;
    }


    // ArchiveFile abstract method implementations
    // -------------------------------------------

    // Implementation of the abstract method to open the archive file.
    void open() throws IOException {
        init(); // Resets Vector and internal state

        // Logic check: If we have no memory file AND no path, we can't do anything.
        if (memfile == null && pathname == null) return;

        // Try to open physical file ONLY if we don't have a memory file
        if (memfile == null) {
            try {
                file = new RandomAccessFile(pathname, "r");
            } catch (Exception e) {
                throw new IOException("RandomAccessFile failed: " + e.getMessage());
            }
        }

        // The Scanning Loop
        try {
            // Ensure memfile is at start if it exists
            if (memfile != null) memfile.seek(0);
            if (file != null) file.seek(0);

            for (; ; ) {
                LhaEntry h = null;
                if (file != null) h = new LhaEntry(file, this);
                else if (memfile != null) h = new LhaEntry(memfile, this);

                if (h == null) break;
                addEntry(h);
                size += h.getSize();
                compressedsize += h.getCompressedSize();
                h.skip();
            }
        } catch (EOFException e) {
            // This is the expected way the loop ends
            open = true;
        } catch (IOException e) {
            Log.e("KiSS_JAVA", "Open failed at entry: " + e.getMessage());
            e.printStackTrace(); // THIS WILL TELL US THE EXACT LINE IN LHAENTRY
            // If we found NO entries, then it's a truly invalid LZH
            if (contents == null || contents.size() == 0) {
                throw new IOException("Invalid LZH archive: No entries found. " + e.getMessage());
            }
            // Otherwise, we just hit the end of the file slightly messily
            open = true;
        }
    }



    // Returns an input stream for reading the uncompressed contents of
    // the specified LHA file entry.

    public InputStream getInputStream(ArchiveEntry le) throws IOException {
        if (!isOpen()) return null;
        if (!(le instanceof LhaEntry)) return null;
        byte[] buf = null;
        ByteArrayOutputStream out = null;
        InputStream in = ((LhaEntry) le).getCompressedInputStream();

        // Uncompress the data.  We uncompress all the data to a memory buffer
        // and then return an input stream that can be used to read the contents
        // of the buffer.

        try {
            switch (((LhaEntry) le).getMethod()) {
                case LhaEntry.LH0:
                    buf = new byte[(int) le.getSize()];
                    in.read(buf, 0, (int) le.getSize());
                    ((LhaEntry) le).setCrc32(computeCRC32(buf));
                    in.close();
                    return new ByteArrayInputStream(buf);
                case LhaEntry.LH1:
                    out = new ByteArrayOutputStream((int) le.getSize());
                    new Lzhuf().decode(in, out);
                    buf = out.toByteArray();
                    ((LhaEntry) le).setCrc32(computeCRC32(buf));
                    in.close();
                    out.close();
                    return new ByteArrayInputStream(buf);
                case LhaEntry.LH5:
                case LhaEntry.LH6:
                case LhaEntry.LH7:
                    out = new ByteArrayOutputStream((int) le.getSize());
                    (new Lhhuf((int) le.getSize(), ((LhaEntry) le).getMethod())).decode(in, out);
                    buf = out.toByteArray();
                    ((LhaEntry) le).setCrc32(computeCRC32(buf));
                    in.close();
                    out.close();
                    return new ByteArrayInputStream(buf);

                default:
                    throw new ZipException("LHA unimplemented method: "
                            + ((LhaEntry) le).getMethodText());
            }
        }

        // Catch general Exceptions and prefix by element name.

        catch (Exception e) {
            if (in != null) in.close();
            if (out != null) out.close();
            String name = (le != null) ? le.getName() : null;
            name = (name == null) ? "" : name + " ";
            throw new IOException(name + e.getMessage());
        }
    }


    // Returns an output stream for writing the compressed contents of
    // the specified LHA file.

    OutputStream getOutputStream(ArchiveEntry le) throws IOException {
        return new LhaOutputStream(this);
    }


    // Close the LHA random access file only if no media connections exist.

    void close() throws IOException {
        if (connections > 0) return;
        if (file != null) file.close();
        if (memfile != null) memfile.close();
        file = null;
        memfile = null;
        open = false;
    }


    // Release the LHA contents reference.

    void flush() {
        contents = null;
    }


    // Returns the file open state.

    boolean isOpen() {
        Object o = getRandomAccessFile();
        if (o != null) return true;
        o = getMemFile();
        return (o != null);
    }


    // Calculate the CRC32 of the LHA entry.

    long computeCRC32(byte[] buf) {
        Checksum crc32 = new CRC32();
        crc32.update(buf, 0, buf.length);
        return crc32.getValue();
    }
}
