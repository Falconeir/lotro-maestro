package com.digero.maestro.abctomidi;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Track;

import com.digero.maestro.MaestroMain;
import com.digero.maestro.abc.LotroInstrument;
import com.digero.maestro.abc.TimingInfo;
import com.digero.maestro.midi.KeySignature;
import com.digero.maestro.midi.MidiConstants;
import com.digero.maestro.midi.MidiFactory;
import com.digero.maestro.midi.TimeSignature;
import com.digero.maestro.util.ParseException;

public class AbcToMidi {
	public static void main(String[] args) throws Exception {
		AbcToMidi a2m = new AbcToMidi();

		FileInputStream in = new FileInputStream(
				"C:\\Users\\Ben\\Documents\\The Lord of the Rings Online\\Music\\jesujoy" + ".abc");

		boolean useLotroInstruments = true;
		Sequence song = a2m.convert(in, useLotroInstruments);
		in.close();

		Sequencer sequencer = MidiSystem.getSequencer(false);
		sequencer.open();
		Synthesizer synth = null;

		if (useLotroInstruments) {
			Soundbank lotroSoundbank = MidiSystem.getSoundbank(MaestroMain.class
					.getResourceAsStream("midi/synth/LotroInstruments.sf2"));
			Soundbank lotroDrumbank = MidiSystem.getSoundbank(MaestroMain.class
					.getResourceAsStream("midi/synth/LotroDrums.sf2"));
			synth = MidiSystem.getSynthesizer();
			sequencer.getTransmitter().setReceiver(synth.getReceiver());
			synth.open();
			synth.unloadAllInstruments(lotroSoundbank);
			synth.loadAllInstruments(lotroSoundbank);
			synth.unloadAllInstruments(lotroDrumbank);
			synth.loadAllInstruments(lotroDrumbank);
		}
		else {
			sequencer.getTransmitter().setReceiver(MidiSystem.getReceiver());
		}

		sequencer.setSequence(song);
		sequencer.start();

		while (sequencer.isRunning()) {
			Thread.sleep(500);
		}
		sequencer.close();

		if (synth != null)
			synth.close();
	}

	public AbcToMidi() {

	}

	private enum Dynamics {
		// Velocities referenced form http://en.wikipedia.org/wiki/Dynamics_(music)
		ppp(16), pp(33), p(49), mp(64), mf(80), f(96), ff(112), fff(126);

		public final int velocity;

		private Dynamics(int velocity) {
			this.velocity = velocity;
		}
	}

	private static final Pattern INFO_PATTERN = Pattern.compile("^([A-Z]):\\s*(.*)\\s*$");
	private static final int INFO_TYPE = 1;
	private static final int INFO_VALUE = 2;

	private static final Pattern NOTE_PATTERN = Pattern.compile("(_{1,2}|=|\\^{1,2})?" + "([zA-Ga-g])"
			+ "(,{1,5}|'{1,5})?" + "((\\d+)?(/\\d{0,2})?|(//))?" + "(-)?");
	private static final int NOTE_ACCIDENTAL = 1;
	private static final int NOTE_LETTER = 2;
	private static final int NOTE_OCTAVE = 3;
//	private static final int NOTE_LENGTH = 4;
	private static final int NOTE_LEN_NUMER = 5;
	private static final int NOTE_LEN_DENOM = 6;
	private static final int NOTE_LEN_DOUBLECUT = 7;
	private static final int NOTE_TIE = 8;

	// Lots of prime factors for divisibility goodness
	private static final long DEFAULT_NOTE_PULSES = (2 * 2 * 2 * 2 * 2 * 2) * (3 * 3) * 5 * 7;

	public static List<String> readLines(InputStream stream) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		BufferedReader rdr = new BufferedReader(new InputStreamReader(stream));
		String line;
		while ((line = rdr.readLine()) != null) {
			lines.add(line);
		}
		return lines;
	}

	public Sequence convert(InputStream abcStream, boolean useLotroInstruments) throws IOException, ParseException {
		return convert(readLines(abcStream), useLotroInstruments, null);
	}

	public Sequence convert(Iterable<String> lines, boolean useLotroInstruments,
			Map<Integer, LotroInstrument> instrumentOverrideMap) throws ParseException {
		TuneInfo info = new TuneInfo();
		Sequence seq = null;
		Track track = null;

		int lineNumber = 0;
		int partStartLine = 0;
		int channel = 0;
		int trackNumber = 0;

		long chordStartTick = 0;
		long chordEndTick = 0;
		long PPQN = 0;
		long MPQN = 0;
		Map<Integer, Integer> tiedNotes = new HashMap<Integer, Integer>(); // noteId => (line << 16) | column
		Map<Integer, Integer> accidentals = new HashMap<Integer, Integer>(); // noteId => deltaNoteId
		List<MidiEvent> noteOffEvents = new ArrayList<MidiEvent>();
		for (String line : lines) {
			lineNumber++;

			int comment = line.indexOf('%');
			if (comment >= 0)
				line = line.substring(0, comment);
			if (line.trim().length() == 0)
				continue;

			Matcher infoMatcher = INFO_PATTERN.matcher(line);
			if (infoMatcher.matches()) {
				char type = infoMatcher.group(INFO_TYPE).charAt(0);
				String value = infoMatcher.group(INFO_VALUE).trim();

				try {
					switch (type) {
					case 'X':
						for (int lineAndColumn : tiedNotes.values()) {
							throw new ParseException("Tied note does not connect to another note",
									lineAndColumn >>> 16, lineAndColumn & 0xFFFF);
						}

						accidentals.clear();
						noteOffEvents.clear();

						info.newPart(Integer.parseInt(value));
						trackNumber++;
						partStartLine = lineNumber;
						chordStartTick = 0;
						track = null; // Will create a new track after the header is done
						if (instrumentOverrideMap != null && instrumentOverrideMap.containsKey(trackNumber)) {
							info.setInstrument(instrumentOverrideMap.get(trackNumber));
						}
						break;
					case 'T':
						if (track != null)
							throw new ParseException("Can't specify the title in the middle of a part", lineNumber, 0);

						info.setTitle(value);
						if (instrumentOverrideMap == null || !instrumentOverrideMap.containsKey(trackNumber)) {
							info.findInstrumentName(value);
						}
						break;
					case 'K':
						info.setKey(value);
						if (info.getKey().sharpsFlats != 0) {
							throw new ParseException("Only C major and A minor are currently supported", lineNumber,
									infoMatcher.start(INFO_VALUE));
						}
						break;
					case 'L':
						info.setNoteDivisor(value);
						break;
					case 'M':
						TimeSignature meter = info.getMeter();
						info.setMeter(value);
						if (seq != null && !info.getMeter().equals(meter))
							throw new ParseException("The meter can't change during the song", lineNumber);
						break;
					case 'Q':
						int tempo = info.getTempo();
						info.setTempo(value);
						if (seq != null && info.getTempo() != tempo)
							throw new ParseException("The tempo can't change during the song", lineNumber);
						break;
					case 'I':
						if (instrumentOverrideMap == null || !instrumentOverrideMap.containsKey(trackNumber)) {
							info.findInstrumentName(value);
						}
						break;
					}
				}
				catch (IllegalArgumentException iae) {
					throw new ParseException(iae.getMessage(), lineNumber, infoMatcher.start(INFO_VALUE));
				}
			}
			else {
				// The line contains notes

				if (seq == null) {
					try {
						PPQN = DEFAULT_NOTE_PULSES * info.getNoteDivisor() / 4;
						MPQN = (long) TimingInfo.ONE_MINUTE_MICROS / info.getTempo();
						seq = new Sequence(Sequence.PPQ, (int) PPQN);

						// Track 0: Title and meta info
						Track track0 = seq.createTrack();
						track0.add(MidiFactory.createTrackNameEvent(info.getTitle()));
						track0.add(MidiFactory.createTempoEvent((int) MPQN, 0));

						track = null;
					}
					catch (InvalidMidiDataException mde) {
						throw new ParseException("Midi Error: " + mde.getMessage());
					}
				}

				if (track == null) {
					channel = seq.getTracks().length;
					if (channel >= MidiConstants.DRUM_CHANNEL)
						channel++;
					if (channel > MidiConstants.CHANNEL_COUNT - 1)
						throw new ParseException("Too many parts (max = " + (MidiConstants.CHANNEL_COUNT - 1) + ")",
								partStartLine);
					track = seq.createTrack();

					track.add(MidiFactory.createTrackNameEvent(info.getPartNumber() + ". " + info.getTitle()));
					track.add(MidiFactory.createProgramChangeEvent(info.getInstrument().midiProgramId, channel, 0));
				}

				Matcher m = NOTE_PATTERN.matcher(line);
				int i = 0;
				boolean inChord = false;
//				int tupletP = 0, tupletQ = 0, tupletR = 0;
				Tuplet tuplet = null;
				while (true) {
					boolean found = m.find(i);
					int parseEnd = found ? m.start() : line.length();
					// Parse anything that's not a note
					for (; i < parseEnd; i++) {
						char ch = line.charAt(i);
						if (Character.isWhitespace(ch)) {
							if (inChord)
								throw new ParseException("Unexpected whitespace inside a chord", lineNumber, i);
							continue;
						}

						switch (ch) {
						case '[': // Chord start
							if (inChord)
								throw new ParseException("Unexpected '" + ch + "' inside a chord", lineNumber, i);
							inChord = true;
							break;

						case ']': // Chord end
							if (!inChord)
								throw new ParseException("Unexpected '" + ch + "'", lineNumber, i);
							inChord = false;
							chordStartTick = chordEndTick;
							break;

						case '|': // Bar line
							accidentals.clear();
							if (i + 1 < line.length() && line.charAt(i + 1) == ']')
								i++; // Skip |]
							break;

						case '+': {
							int j = line.indexOf('+', i + 1);
							if (j < 0) {
								throw new ParseException("There is no matching '+'", lineNumber, i);
							}
							try {
								info.setDynamics(line.substring(i + 1, j));
							}
							catch (IllegalArgumentException iae) {
								throw new ParseException("Unsupported +decoration+", lineNumber, i);
							}
							i = j;
							break;
						}

						case '(':
							if (tuplet != null)
								throw new ParseException("Unexpected '" + ch + "' before end of tuplet", lineNumber, i);

							try {
								for (int j = i + 1; j < line.length(); j++) {
									if (line.charAt(i) != ':' && !Character.isDigit(line.charAt(i))) {
										tuplet = new Tuplet(line.substring(i + 1, j + 1), info.getMeter().isCompound());
										i = j;
										break;
									}
								}
							}
							catch (IllegalArgumentException e) {
								throw new ParseException("Invalid tuplet", lineNumber, i);
							}
							break;

						default:
							throw new ParseException("Unknown/unexpected character '" + ch + "'", lineNumber, i);
						}
					}

					if (i >= line.length())
						break;

					// The matcher might find +f+, +ff+, or +fff+ and think it's a note
					if (i > m.start())
						continue;

					// Parse the note
					int numerator;
					int denominator;

					if (m.group(NOTE_LEN_DOUBLECUT) != null) {
						numerator = 1;
						denominator = 4;
					}
					else {
						numerator = (m.group(NOTE_LEN_NUMER) == null) ? 1 : Integer.parseInt(m.group(NOTE_LEN_NUMER));
						String denom = m.group(NOTE_LEN_DENOM);
						if (denom == null)
							denominator = 1;
						else if (denom.equals("/"))
							denominator = 2;
						else
							denominator = Integer.parseInt(denom.substring(1));
					}

					if (tuplet != null) {
						tuplet.r--;
						numerator *= tuplet.q;
						denominator *= tuplet.p;
						if (tuplet.r == 0)
							tuplet = null;
					}

					long noteEndTick = chordStartTick + DEFAULT_NOTE_PULSES * numerator / denominator;
					// A chord is as long as its shortest note
					if (chordEndTick == chordStartTick || noteEndTick < chordEndTick)
						chordEndTick = noteEndTick;

					char noteLetter = m.group(NOTE_LETTER).charAt(0);
					String octaveStr = m.group(NOTE_OCTAVE);
					if (octaveStr == null)
						octaveStr = "";
					if (noteLetter == 'z') {
						if (m.group(NOTE_ACCIDENTAL) != null && m.group(NOTE_ACCIDENTAL).length() > 0) {
							throw new ParseException("Unexpected accidental on a rest", lineNumber, m
									.start(NOTE_ACCIDENTAL));
						}
						if (octaveStr.length() > 0) {
							throw new ParseException("Unexpected octave indicator on a rest", lineNumber, m
									.start(NOTE_OCTAVE));
						}
					}
					else {
						int octave;
						if (Character.isUpperCase(noteLetter)) {
							// Upper case letters: octaves 3 and below (with commas)
							if (octaveStr.indexOf('\'') >= 0)
								throw new ParseException("Invalid octave marker", lineNumber, m.start(NOTE_OCTAVE));
							octave = 3 - octaveStr.length();
						}
						else {
							// Lower case letters: octaves 4 and above (with apostrophes)
							if (octaveStr.indexOf(',') >= 0)
								throw new ParseException("Invalid octave marker", lineNumber, m.start(NOTE_OCTAVE));
							octave = 4 + octaveStr.length();
						}

						int[] noteDelta = {
								9, 11, 0, 2, 4, 5, 7
						};

						int noteId = (octave + 1) * 12 + noteDelta[Character.toLowerCase(noteLetter) - 'a'];

						int lotroNoteId = noteId;
						if (!useLotroInstruments)
							noteId += 12 * info.getInstrument().octaveDelta;

						if (m.group(NOTE_ACCIDENTAL) != null) {
							if (m.group(NOTE_ACCIDENTAL).startsWith("_"))
								accidentals.put(noteId, -m.group(NOTE_ACCIDENTAL).length());
							else if (m.group(NOTE_ACCIDENTAL).startsWith("^"))
								accidentals.put(noteId, m.group(NOTE_ACCIDENTAL).length());
							else if (m.group(NOTE_ACCIDENTAL).equals("="))
								accidentals.remove(noteId);
						}

						Integer accidental = accidentals.get(noteId);
						if (accidental != null)
							noteId += accidental;

						// Check for overlapping notes, and remove extra note off events
						Iterator<MidiEvent> noteOffIter = noteOffEvents.iterator();
						while (noteOffIter.hasNext()) {
							MidiEvent evt = noteOffIter.next();
							if (evt.getTick() <= chordStartTick) {
								noteOffIter.remove();
								continue;
							}

							int noteOffId = ((ShortMessage) evt.getMessage()).getData1();
							if (noteOffId == noteId) {
								track.remove(evt);
								noteOffIter.remove();
								break;
							}
						}

						if (!tiedNotes.containsKey(noteId)) {
							track.add(MidiFactory.createNoteOnEventEx(noteId, channel, info.getDynamics().velocity,
									chordStartTick));
						}

						if (m.group(NOTE_TIE) != null) {
//							tiedNotes.add(noteId);
							int lineAndColumn = lineNumber << 16 | m.start();
							tiedNotes.put(noteId, lineAndColumn);
						}
						else {
							// Increase the note length to a mininum amount
							long noteEndTickTmp;
							if (!info.getInstrument().isSustainable(lotroNoteId)) {
								noteEndTickTmp = chordStartTick + TimingInfo.ONE_SECOND_MICROS * PPQN / MPQN;
							}
							else {
								noteEndTickTmp = Math.max(noteEndTick + 1, chordStartTick
										+ TimingInfo.ONE_SECOND_MICROS / 4 * PPQN / MPQN);
							}

							MidiEvent noteOff = MidiFactory.createNoteOffEventEx(noteId, channel,
									info.getDynamics().velocity, noteEndTickTmp);
							track.add(noteOff);

							noteOffEvents.add(noteOff);

							tiedNotes.remove((Integer) noteId);
						}
					}

					if (!inChord)
						chordStartTick = noteEndTick;
					i = m.end();
				}

				if (tuplet != null)
					throw new ParseException("Tuplet not finished by end of line", lineNumber, i);

				if (inChord)
					throw new ParseException("Chord not closed at end of line", lineNumber, i);
			}
		}

		if (seq == null)
			throw new ParseException("The song contains no notes", lineNumber);

		if (tiedNotes.size() > 0) {
			throw new ParseException(tiedNotes.size() + " unresolved note tie(s) " + "in the part beginning on line "
					+ partStartLine, lineNumber);
		}

		return seq;
	}

	private static void dbgout(String text) {
		System.out.println(text);
	}

	// From http://abcnotation.com/abc2mtex/abc.txt:
	//
	//   Duplets, triplets, quadruplets, etc.
	//   ====================================
	// These can be simply coded with the notation (2ab  for  a  duplet,
	// (3abc  for  a triplet or (4abcd for a quadruplet, etc., up to (9.
	// The musical meanings are:
	//
	//  (2 2 notes in the time of 3
	//  (3 3 notes in the time of 2
	//  (4 4 notes in the time of 3
	//  (5 5 notes in the time of n
	//  (6 6 notes in the time of 2
	//  (7 7 notes in the time of n
	//  (8 8 notes in the time of 3
	//  (9 9 notes in the time of n
	//
	// If the time signature is compound (3/8, 6/8, 9/8, 3/4, etc.) then
	// n is three, otherwise n is two.
	//
	// More general tuplets can be specified  using  the  syntax  (p:q:r
	// which  means  `put  p  notes  into  the  time of q for the next r
	// notes'.  If q is not given, it defaults as above.  If  r  is  not
	// given,  it  defaults  to p.  For example, (3:2:2 is equivalent to
	// (3::2 and (3:2:3 is equivalent to (3:2 , (3 or even (3:: .   This
	// can  be  useful  to  include  notes of different lengths within a
	// tuplet, for example (3:2:2G4c2 or (3:2:4G2A2Bc and also describes
	// more precisely how the simple syntax works in cases like (3D2E2F2
	// or even (3D3EF2. The number written over the tuplet is p.
	private static class Tuplet {
		public int p;
		public int q;
		public int r;

		public Tuplet(String str, boolean compoundMeter) {
			try {
				String[] parts = str.split(":");
				if (parts.length < 1 || parts.length > 3)
					throw new IllegalArgumentException();

				p = Integer.parseInt(parts[0]);

				if (p < 2 || p > 9)
					throw new IllegalArgumentException();

				if (parts.length >= 2)
					q = Integer.parseInt(parts[1]);
				else if (p == 3 || p == 6)
					q = 2;
				else if (p == 2 || p == 4 || p == 8)
					q = 3;
				else if (p == 5 || p == 7 || p == 9)
					q = compoundMeter ? 3 : 2;
				else
					throw new IllegalArgumentException();

				if (parts.length >= 3)
					r = Integer.parseInt(parts[2]);
				else
					r = p;
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException(e);
			}
		}
	}

	private static class TuneInfo {
		private int partNumber;
		private String title;
		private KeySignature key;
		private int noteDivisor;
		private TimeSignature meter;
		private int tempo;
		private LotroInstrument instrument;
		private Dynamics dynamics;

		public TuneInfo() {
			title = "";
			key = KeySignature.C_MAJOR;
			noteDivisor = 8;
			meter = TimeSignature.FOUR_FOUR;
			tempo = 120;
			instrument = LotroInstrument.LUTE;
			dynamics = Dynamics.mf;
		}

		public void newPart(int partNumber) {
			this.partNumber = partNumber;
			instrument = LotroInstrument.LUTE;
			dynamics = Dynamics.mf;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public void setKey(String str) {
			this.key = new KeySignature(str);
		}

		public void setNoteDivisor(String str) {
			String[] parts = str.trim().split("[/:| ]");
			if (parts.length != 2) {
				throw new IllegalArgumentException("The string: \"" + str
						+ "\" is not a valid default note length (expected format: L:1/4)");
			}
			int numerator = Integer.parseInt(parts[0]);
			int denominator = Integer.parseInt(parts[1]);
			if (numerator != 1)
				throw new IllegalArgumentException("The numerator of the default note length must be 1");
			this.noteDivisor = denominator;
		}

		public void setMeter(String str) {
			this.meter = new TimeSignature(str);
			this.noteDivisor = (4 * meter.numerator / meter.denominator) < 3 ? 16 : 8;
		}

		public void setTempo(String str) {
			int tempo;
			try {
				tempo = Integer.parseInt(str);
			}
			catch (NumberFormatException nfe) {
				throw new IllegalArgumentException("Unable to read tempo. The only supported format is Q:120");
			}

			this.tempo = tempo;
		}

		private static Pattern instrRegex = null;

		public boolean findInstrumentName(String str) {
			if (instrRegex == null) {
				String regex = "";
				for (LotroInstrument instr : LotroInstrument.values()) {
					regex += "|" + instr;
				}
				regex = "\\b(" + regex.substring(1) + ")\\b";
				instrRegex = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
			}

			Matcher m = instrRegex.matcher(str);
			if (m.find()) {
				this.instrument = LotroInstrument.valueOf(m.group(1).toUpperCase());
				return true;
			}
			return false;
		}

		public void setInstrument(LotroInstrument instrument) {
			this.instrument = instrument;
		}

		public void setDynamics(String str) {
			dynamics = Dynamics.valueOf(str);
		}

		public int getPartNumber() {
			return partNumber;
		}

		public String getTitle() {
			return title;
		}

		public KeySignature getKey() {
			return key;
		}

		public int getNoteDivisor() {
			return noteDivisor;
		}

		public TimeSignature getMeter() {
			return meter;
		}

		public int getTempo() {
			return tempo;
		}

		public LotroInstrument getInstrument() {
			return instrument;
		}

		public Dynamics getDynamics() {
			return dynamics;
		}
	}
}