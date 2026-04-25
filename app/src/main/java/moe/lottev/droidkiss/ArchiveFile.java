package moe.lottev.droidkiss;

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


/*
  ArchiveFile class
  <p>
  Purpose:
  <p>
  This class is an abstract class for KiSS data archive files.  Extensions
  of this class define specific archive file types.  These may be ZIP files,
  LHA files, JAR files, or even uncompressed directory files.
  <p>
  An archive file is an object that manages a file container.  This is either
  a compressed file or a file directory.  The container has a name and it
  contains many file elements.
  <p>
  This object manages the state and contents of the file container.

 */

import java.util.*;
import java.io.*;

public abstract class ArchiveFile {
    protected String pathname = null;            // Archive file path name
    @SuppressWarnings("rawtypes")
    protected Vector contents = null;            // File contents list
    protected MemFile memfile = null;        // Memory file for URL input
    protected long size = 0;                        // Total uncompressed bytes
    protected long compressedsize = 0;            // Total compressed bytes
    protected int connections = 0;                // Media file use count
    protected int opencount = 0;                    // File open count
    protected boolean open = false;          // True if archive is open
    @SuppressWarnings("rawtypes")
    protected Hashtable key = new Hashtable(200, 0.855f); // Entry index

    // Object utility methods
    // ----------------------

    // Return the fully qualified archive file path name.
    static boolean isArchive(String name) {
        if (name == null) return false;
        //noinspection RegExpRedundantEscape
        name = name.replaceFirst("[\\#\\?].*$", "");  // no query or ref
        int n = name.lastIndexOf('.');
        String ext = (n < 0) ? "" : name.substring(n).toLowerCase();
        switch (ext) {
            case ".gzip":
            case ".zip":
            case ".jar":
                return true;
        }
        return ".lzh".equals(ext);
    }

    // A function to determine if the extension is for a directory.
    String getDirectoryName() {
        if (pathname == null) return null;
        if (isArchive()) {
            File f = new File(pathname);
            return f.getParent();
        }
        return pathname;
    }

    // A function to determine if the extension has a palette.
    String getPath() {
        return pathname;
    }

    // A function to return the known palette file extensions.
    MemFile getMemFile() {
        return memfile;
    }

    // A function to return the known video file extensions.
    boolean isArchive() {
        return isArchive(pathname);
    }

    // This function adds an entry to the archive file contents.  For
    // directory files the archive entry directory name is set to match
    // this archive file directory name as this entry can be written as
    // a new element in this archive file.  Entries are added only if they
    // do not currently exist.  Added entries are retained in the hash key
    // table for getEntry search optimization.
    void addEntry(ArchiveEntry ze) {
        if (ze == null) return;
        if (contents == null) init();
        String s = ze.getPath();
        if (s == null) return;
        if (contents.contains(ze)) return;
        Object o = key.get(s.toLowerCase());
        if (o != null) return;
        if (this instanceof DirFile)
            ze.setDirectory(getDirectoryName());
        //noinspection unchecked
        contents.addElement(ze);
        //noinspection unchecked
        key.put(s.toLowerCase(), ze);
    }

    // Initialize the archive contents.
    void init() {
        //noinspection rawtypes
        contents = new Vector();
        //noinspection rawtypes
        key = new Hashtable(200, 0.855f);
    }

    // Abstract class methods
    // ----------------------

    // Returns an input stream for reading the uncompressed contents of
    // the specified file entry.
    public abstract InputStream getInputStream(ArchiveEntry e) throws IOException;

    // Close the archive file contents.
    abstract void close() throws IOException;

    // Flush the archive file contents.
    abstract void flush();

    // Open the archive file contents.
    abstract void open() throws IOException;

    // Return the file open state.
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    abstract boolean isOpen();

    // The toString method returns a string representation of this object.
    // This is the name of the archive file path.
    @SuppressWarnings("NullableProblems")
    public String toString() {
        return (pathname == null) ? "Unknown" : pathname;
    }
}