/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.marc4j;

import java.io.*;
import java.util.regex.*;
import java.util.HashMap;

import org.marc4j.MarcException;
import org.marc4j.MarcReader;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.Leader;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.marc4j.marc.VariableField;
import org.marc4j.marc.impl.Verifier;
import org.marc4j.marc.DataField;


/**
 *
 * @author dueberb
 */
public class MarcAlephSequentialReader implements MarcReader {

  private MarcFactory factory;
  public BufferedReader reader;
  private int currentline = 0;
  private ASLine firstLine = null;
  private Record record;
  private String currentBibnum = null; // Bibnum of the current record
  // Match on the line or its components
  // Make sure to only match on UNIX_NEWLINEs (otherwise stuff like unicode paragraph break
  // (U+2029) will cause a break in the regexp.
  private static Pattern lineMatch = Pattern.compile("^(.{9}) (.{3})(.)(.) . (.*)$", Pattern.UNIX_LINES);
  private static Pattern subMatch = Pattern.compile("\\$\\$([a-z0-9])(.*?)(?=\\$\\$|\\Z)", Pattern.UNIX_LINES);
  private static Pattern legalIndicator = Pattern.compile("^[ 0-9]$");
  private static Pattern legalDataFieldData = Pattern.compile("^\\$\\$.*$", Pattern.UNIX_LINES);
  // BibNumTag -- where and how to store the bibnum (usually in the 001)
  private String bnt;
  private Boolean replaceExistingBNT;
  public ErrorHandler errors = new ErrorHandler();
  // Set up a list of valid control fields; include FMT
  private static HashMap<String, Boolean> controlFields = new HashMap<String, Boolean>();

  // Allow FMT as a control tag as well as 001-009
  static {
    controlFields.put("FMT", true);
    for (int i = 1; i <= 9; i++) {
      controlFields.put("00" + Integer.toString(i), true);
    }
  }

  /**
   * Inner class to keep track of the basic parts of a
   * AlephSequential line: the bibnum (internal identifier), tag,
   * indicators one and two, and the data.
   *
   * Note that there will be no indicators for a control field; they'll
   * just be spaces.
   */
  public class ASLine {

    public String bibnum;
    public String tag;
    public String ind1;
    public String ind2;
    public String data;

    @Override
    public String toString() {
      return bibnum + "|" + tag + "|" + ind1 + ind2 + "|" + data;
    }
  }

  /**
   * Class constructor, defaulting to taking out all 001 fields and
   * inserting a single 001 in each record with the value of the bibnumber.
   *
   * @param instream Input stream of the AlephSequential records
   */
  public MarcAlephSequentialReader(InputStream instream)
      throws java.io.UnsupportedEncodingException, java.io.IOException {
    this(instream, "001", true);
  }

  /**
   * Class constructor.
   *
   * @param instream The input stream.
   * @param bibnumtag The tag in which to put the bibnum. 'null' means
   *                  don't do it at all. If you provide a datafield tag,
   *                  the bibnum will go in the 'a' subfield.
   * @param replace Whether or not to remove all fields with tag
   *                <code>bibnumtag</code> before adding new tag.
   * @throws IOException if the file can't be opened or read
   *
   */
  public MarcAlephSequentialReader(InputStream instream, String bibnumtag, Boolean replace)
      throws java.io.UnsupportedEncodingException,
      IOException {
    factory = MarcFactory.newInstance();
    reader = new BufferedReader(new InputStreamReader(instream, "UTF-8"));
    bnt = bibnumtag;
    replaceExistingBNT = replace;
    errors.reset();
    firstLine = this.nextLine();
    if (firstLine == null) {
      throw new MarcException("Invalid first line");
    }
  }

  /**
   * Is there more to read?
   *
   * @return whether or not the input stream has more to offer
   */
  public boolean hasNext() {
    try {
      return reader.ready() && firstLine != null;
    }
    catch (IOException e) {
      return false;
    }
  }

  /**
   * Get the next record by reading lines and adding their
   * data to a new record until the bibnum changes
   *
   * @return the new record
   */
  public Record next() {
    errors.reset();
    if (firstLine == null) {
      return null;
    }
    // Get the current bibnum where we can use it and create an empty Record
    currentBibnum = firstLine.bibnum;

    // A new empty record
    record = factory.newRecord();

    // Add the first line
    addDataToRecord(record, firstLine);

    // Add the subsequent lines until we get a different bibnum
    while (true) {
      try {
        ASLine aline = nextLine();
        
        if (!aline.bibnum.equals(currentBibnum)) {
          firstLine = aline;
          break;
        }
        // Add this line
        addDataToRecord(record, aline);

      }
      catch (IOException e) { // EOF?
        firstLine = null;
        break;
      }
    }

    // Construct a controlfield or datafield (with subfield 'a')
    // for the bibnum, if need be

    if (bnt != null) {
      if (controlFields.containsKey(bnt)) {
        record.addVariableField(factory.newControlField(bnt, currentBibnum));
      }
      else {
        Subfield s = factory.newSubfield();
        s.setCode('a');
        s.setData(currentBibnum);
        DataField d = factory.newDataField(bnt, ' ', ' ');
        d.addSubfield(s);
      }
    }

    return record;
  }

  /**
   * Parse a line into its basic components: bibnum, tag, indicators, and data
   *
   * @param line The line of data, sans trailing newline.
   * @return An ASLine reflecting the data.
   * @throws IllegalArgumentException If the line has illegal structure,
   *                                  or if an indicator is illegal
   */
  public ASLine parseLine(String line) throws IllegalArgumentException {

    ASLine asline = new ASLine();

    Matcher m = lineMatch.matcher(line);

    if (m == null || !m.matches() || m.groupCount() != 5) {
      errors.addError(currentBibnum, "line structure", "--", ErrorHandler.MAJOR_ERROR, "(" + currentline + ") " +  line);
      return null;
    }

    asline.bibnum = m.group(1);
    asline.tag = m.group(2);
    asline.ind1 = m.group(3);
    asline.ind2 = m.group(4);
    asline.data = m.group(5);

    // Check for legal indicators
    m = legalIndicator.matcher(asline.ind1);
    if (!m.matches()) {
      errors.addError(asline.bibnum, asline.tag, "--", ErrorHandler.MINOR_ERROR,
          "(" + currentline + ") Illegal first indicator '" + asline.ind1 + "' changed to space");
      asline.ind1 = " ";
    }

    m = legalIndicator.matcher(asline.ind2.toString());
    if (!m.matches()) {
      errors.addError(asline.bibnum, asline.tag, "--", ErrorHandler.MINOR_ERROR,
          "(" + currentline + ") Illegal second indicator '" + asline.ind2 + "' changed to space");
      asline.ind1 = " ";
    }

    return asline;
  }

  /**
   *
   * @param r The record to add data to
   * @param a The curent ALine
   * @throws IllegalArgumentException
   */
  public void addDataToRecord(Record r, ASLine a) throws IllegalArgumentException {
    // Do nothing if we've got the bibnumtag
    if (a.tag.equals(this.bnt) && this.replaceExistingBNT) {
      return; // do nothing
    }

    // Deal with the leader
    if (a.tag.equals("LDR")) {
      String data = a.data.replace('^', ' ');
      r.setLeader(factory.newLeader(data));
      return;
    }

    // If it's a control field, just add the data
    if (controlFields.containsKey(a.tag)) {
      String data = a.data.replace('^', ' ');
      r.addVariableField(factory.newControlField(a.tag, data));
      return;
    }

    //otherwise we have a datafiled.

    // Is it legal (starts with $$)?

    Matcher m = legalDataFieldData.matcher(a.data);
    if (!m.matches()) {

      errors.addError(currentBibnum, a.tag, "--", ErrorHandler.MAJOR_ERROR,
          "(" + currentline + ") Malformed variable field data: " + a.data);
      return;
    }


    DataField d = factory.newDataField(a.tag, a.ind1.charAt(0), a.ind2.charAt(0));
    addSubs(d, a.data);
    record.addVariableField(d);

  }

  /**
   * Given the datafield d and the string that is its data, parse out
   * the subfields and add them to d.
   *
   * The subfields are delimited by two dollar signs and the subfield
   * code, e.g., '$$b'. The string must start with 'a valid delmited
   * (e.g., '$$a').
   *
   * @param d the DataField to add to
   * @param data The data, of the form "$$a...$$b..." etc.
   */
  public void addSubs(DataField d, String data) {
    Matcher m = subMatch.matcher(data);

    while (m.find()) {
      Subfield s = factory.newSubfield();
      s.setCode(m.group(1).toCharArray()[0]);
      s.setData(m.group(2));
      d.addSubfield(s);
    }
  }

  /**
   * Just get the next (newline-delimited) line from the file and
   * parse it out
   * @return The newly-parsed line
   * @throws IOException if we're at EOF or something wacky occurred
   */
  public ASLine nextLine() throws IOException {
    if (!reader.ready()) {
      throw new IOException();
    }

//    currentBibnum = firstLine.bibnum;

    String line = reader.readLine();
    currentline++;
    int startat = currentline;
    ASLine aline = parseLine(line);

    // If it didn't parse, add it as a recoverable error and
    // try again. For a bit, anyway. If there are more than
    // ten lines in a row that are bad, give up.

    if (aline == null) {
      errors.addError(currentBibnum, "BAD", "--", ErrorHandler.MAJOR_ERROR, "(" + currentline  + ") " + line);
      int i = 0;
      while ((aline == null) && (i < 10)) {
        line = reader.readLine();
        aline = parseLine(line);
        currentline++;
        i++;
      }
    }

    // Is it still null? Give up.
    if (aline == null) {
      errors.addError(currentBibnum, "BADFILE", "--", ErrorHandler.FATAL, "(starting at " + startat + ") " +  line);
      throw new IOException("Ten bad lines in a row; maybe not an AlephSequential file? Aborting");
    }

    return aline;
  }
}
