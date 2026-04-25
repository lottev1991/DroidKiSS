package moe.lottev.droidkiss;

// Special thanks to William Miles for his archive extraction logic.
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


/*
  ArchiveEntry class
  <p>
  Purpose:
  <p>
  This is an abstract class for KiSS archive element types.  Each element
  in a KiSS data set is described by an archive entry.  The archive entry
  defines the element characteristics such as compression type, file location
  and so on.  Every archive entry is related back to an archive file.
  <p>
  An archive entry is an object that manages one file element inside an archive
  file.  The archive file is either a compressed file or a file directory.
  <p>
  This object manages the state and contents of the file element.

 */

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.*;
import java.io.*;

@SuppressWarnings("rawtypes")
public abstract class ArchiveEntry
        implements Cloneable, Comparable {
    protected String filename = null;            // Entry file name (unqualified)
    protected String dirname = null;            // Entry directory name
    protected String pathname = null;            // Entry path name (qualified)
    protected String importpath = null;        // Directory path if imported
    protected ArchiveFile archive = null;        // Parent archive file object
    protected MemFile memfile = null;        // Memory file for this archive entry
    protected Vector updated;            // True if file has changed
    protected boolean copy = false;                // True if Kiss object is a copy

    // Constructor

    public ArchiveEntry() {
        updated = new Vector();
    }

    // Object utility methods
    // ----------------------

    // Return the requested element name.
    public String getName() {
        return filename;
    }

    // Return the requested path name.
    void setName(String name) {
        filename = convertSeparator(name);
        if (dirname == null) return;
        File f = new File(dirname, filename);
        pathname = f.getPath();
    }

    // Returns the full path name of the file.
    public String getPath() {
        return pathname;
    }

    // Return the path if imported.
    void setPath(String path) {
        pathname = convertSeparator(path);
        if (path == null) return;
        File f = new File(path);
        filename = f.getName();
        dirname = f.getParent();
    }

    // Set the archive element directory name.
    String convertSeparator(String s) {
        if (s == null) return null;
        s = s.replace('/', File.separatorChar);
        s = s.replace('\\', File.separatorChar);
        return s;
    }

    // Return true if we must save the path when writing an archive file.
    boolean isImported() {
        // True if imported image
        return false;
    }

    // Set the memory file.
    boolean isMemoryFile() {
        return memfile != null;
    }

    // Return true if we have a writing state set.
    public InputStream getInputStream() throws IOException {
        if (isMemoryFile()) return memfile.getInputStream();
        return null;
    }

    // Returns an input stream for reading the uncompressed contents of
    // the specified file entry.
    long getSize() {
        return -1;
    }

    // Returns an output stream for writing the uncompressed contents of
    // the specified file entry.
    long getCompressedSize() {
        return -1;
    }

    // Return the compressed size of the file.
    int getMethod() {
        return -1;
    }

    // Method to determine if the element is a directory file.
    void setDirectory(String dir) {
        dirname = convertSeparator(dir);
        if (filename == null) return;
        File f = new File(dirname, filename);
        pathname = f.getPath();
    }

    // Shallow clone.  This creates a new object where all object references
    // are the same references as found in the original object.
    //noinspection Nullable
    @SuppressWarnings("NullableProblems")
    @Nullable
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            //noinspection CallToPrintStackTrace
            e.printStackTrace();
            return null;
        }
    }

    // Required comparison method for the Comparable interface.  We compare
    // the toString representations of the objects.  This routine returns
    // 0 if the objects are lexographically equal, a number less than 0 if
    // the object is lexographically less than this object, and a number
    // greater than 0 if the object is lexographically greater than this
    // object.
    public int compareTo(Object o) {
        if (!(o instanceof ArchiveEntry)) return -1;
        String s1 = o.toString();
        String s2 = this.toString();
        return (-(s1.compareTo(s2)));
    }

    // The toString method returns a string representation of this object.
    // This is the name of the archive entry.
    @NonNull
    public String toString() {
        return (filename == null) ? "Unknown" : filename.toUpperCase();
    }
}