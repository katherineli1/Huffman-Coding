import java.util.PriorityQueue;

/**
 *	Interface that all compression suites must implement. That is they must be
 *	able to compress a file and also reverse/decompress that process.
 * 
 *	@author Brian Lavallee
 *	@since 5 November 2015
 *  @author Owen Atrachan
 *  @since December 1, 2016
 */
public class HuffProcessor {

	public static final int BITS_PER_WORD = 8;
	public static final int BITS_PER_INT = 32;
	public static final int ALPH_SIZE = (1 << BITS_PER_WORD); // or 256
	public static final int PSEUDO_EOF = ALPH_SIZE;
	public static final int HUFF_NUMBER = 0xface8200;
	public static final int HUFF_TREE  = HUFF_NUMBER | 1;
	public static final int HUFF_COUNTS = HUFF_NUMBER | 2;
	
	public enum Header{TREE_HEADER, COUNT_HEADER};
	public Header myHeader = Header.TREE_HEADER;
	
	/**
	 * Compresses a file. Process must be reversible and loss-less.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be compressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void compress(BitInputStream in, BitOutputStream out){
		int[] counts = readForCounts(in);
		
		int alphabet_size = 0;
		for (int i : counts) {
			if (i > 0) alphabet_size += 1;
		}
	    System.out.println(alphabet_size);
		
	    HuffNode root = makeTreeFromCounts(counts);
	    String[] codings = makeCodingsFromTree(root, "", new String[257]);
	    out.writeBits(BITS_PER_INT, HUFF_NUMBER); // write the bits for HUFF_NUMBER
	    writeHeader(root, out); // write the Huffman tree from a preorder traversal
	    in.reset(); // reset BitInputStream
	    writeCompressedBits(in, codings, out);
	    
//	    throw new HuffException("Compressed file is larger");
	}
	
	/**
	 * Reads a BitInputStream parameter and returns an int[] array of 256 int values 
	 * with the index as the value read from the BitInputStream (int between 0 and 255) 
	 * and the int at that index in the array as frequency (number of times index occurs)
	 */
	public int[] readForCounts(BitInputStream in) {
		int[] freqs = new int[256];
		while (true){
			int val = in.readBits(BITS_PER_WORD);
			if (val == -1) break;
			freqs[val] += 1;
		}
		return freqs;
	}
	
	/**
	 * Takes array of frequencies from readForCounts and returns a HuffNode that is the 
	 * root of the Huffman coding tree
	 */
	public HuffNode makeTreeFromCounts(int[] freqs) {
		PriorityQueue<HuffNode> pq = new PriorityQueue<HuffNode>();
		// call pq.add(new HuffNode(...)) for every 8-bit
        // value that occur one or more times, including PSEUDO_EOF
		for (int i = 0; i < freqs.length; i++) {
			if (freqs[i] > 0) {
				pq.add(new HuffNode(i, freqs[i], null, null));
			}
		}
		// add a HuffNode for PSEUDO_EOF character with a weight of one to pq
		pq.add(new HuffNode(PSEUDO_EOF, 1, null, null));
		
		// generate Huffman coding tree
		while (pq.size() > 1) {
			HuffNode left = pq.remove();
			HuffNode right = pq.remove();
			pq.add(new HuffNode(0, left.weight() + right.weight(), left, right));
		}
		HuffNode root = pq.remove();
		return root; // return root of tree
	}
	
	/**
	 * Takes HuffNode, String, and String[] array parameters and returns a String[] 
	 * array of 257 String values where index (int between 0 and 256) is the value of 
	 * the leaves and the value at that index is the String encoding for that index 
	 * when compressing. (256 is the value of PSEUDO_EOF)
	 */
	public String[] makeCodingsFromTree(HuffNode root, String path, String[] codings) {
		HuffNode current = root;
//		String[] codings = new String[257];
		if (current.left() == null && current.right() == null) {
			codings[current.value()] = path; // add path encoding for an index (leaf value) to codings
			return codings;
		}
		makeCodingsFromTree(current.left(), path + 0, codings);
		makeCodingsFromTree(current.right(), path + 1, codings);
		return codings;
	}
	
	/**
	 * Write the Huffman tree for decompression later using a preorder traversal - 
	 * zero indicates an internal node and one indicates a leaf
	 */
	public void writeHeader(HuffNode root, BitOutputStream out) {
		HuffNode current = root;
		if (current.left() == null && current.right() == null) {
			out.writeBits(1, 1);
			out.writeBits(9, current.value()); // write out value of leaf nodes
		} else {
			out.writeBits(1, 0);
			writeHeader(current.left(), out);
			writeHeader(current.right(), out);
		}
	}
	
	/**
	 * Read in 8-bit chunks, locate the encoding for the chunk in codings, then 
	 * convert the encoding to an int and write it out to the BitOutputStream. 
	 * Write out PSEUDO_EOF at the end
	 */
	public void writeCompressedBits(BitInputStream in, String[] codings, BitOutputStream out) {
		while (true) {
			int bit = in.readBits(BITS_PER_WORD);
			if (bit == -1) break;
			out.writeBits(codings[bit].length(), Integer.parseInt(codings[bit], 2));
		}
		out.writeBits(codings[PSEUDO_EOF].length(), Integer.parseInt(codings[PSEUDO_EOF], 2)); // write PSEUDO_EOF
	}
	
	/**
	 * Decompresses a file. Output file must be identical bit-by-bit to the
	 * original.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be decompressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void decompress(BitInputStream in, BitOutputStream out){
		int id = in.readBits(BITS_PER_INT);
	    // check id to see if valid compressed file
		if (id != HUFF_NUMBER && id != HUFF_TREE) {
			throw new HuffException("File is not properly compressed");
		}
		HuffNode root = readTreeHeader(in);
	    readCompressedBits(in, out, root);
	}
	
	/**
	 * Takes a BitInputStream parameter and returns a HuffNode that is the root 
	 * of the Huffman tree used for decompressing the file. BitInputStream will 
	 * be left ready to read the first bit of the compressed data, i.e., after 
	 * reading the pre-prder/stored tree.
	 */
	public HuffNode readTreeHeader(BitInputStream in) {
		int bit = in.readBits(1);
		if (bit != 0) {
	    	return new HuffNode(in.readBits(9), 0);
	    }
	  	HuffNode left = readTreeHeader(in);
	    HuffNode right = readTreeHeader(in);
	  	return new HuffNode(0, 0, left, right);
	}
	
	/**
	 * Takes three parameters: a BitInputStream (set to read bits representing 
	 * compressed data), a BitOutputStream, and a HuffNode (root of the tree used 
	 * for decompressing). Read one bit at a time, traversing the tree from root 
	 * to leaf depending on bit value (0 = left, 1 = right). Write out leaf values 
	 * as 8-bit values until PSEUDO_EOF is reached
	 */
	public void readCompressedBits(BitInputStream in, BitOutputStream out, HuffNode root) {
	  	HuffNode current = root;
	  	if (current.value() == PSEUDO_EOF) return; // if root of tree is PSEUDO_EOF, do not write any values to BitOutputStream
	  	else {
	  		while (true) {
				int bit = in.readBits(1);
				if (bit == -1) {
					throw new HuffException("bad input, no PSEUDO_EOF");
				}
				else {
					if (bit == 0) current = current.left();
					else current = current.right();
					if (current.left() == null && current.right() == null) {
						if (current.value() == PSEUDO_EOF) break; // stop reading if PSEUDO_EOF is reached
		                else {
		                	out.writeBits(8, current.value()); // write out value of leaf node
		                	current = root; // restart at root
		                }
					}
				}
	  		}
		}
	}
	
	public void setHeader(Header header) {
        myHeader = header;
        System.out.println("header set to "+myHeader);
    }
}