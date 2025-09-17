package notsotiny.nstasm;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import fr.cenotelie.hime.redist.ASTNode;

/**
 * Utility functions
 */
public class ASMUtil {
    
    /**
     * Get the size in bytes of an integer value. <p>
     * For signed/strict examples, the value 0x0000_00FF is size <br>
     * unsigned:            1 byte <br>
     * signed strict:       2 bytes <br>
     * signed non-strict:   1 byte <p>
     * and the value 0xFFFF_FFFF is size <br>
     * unsigned:            4 bytes <br>
     * signed strict:       1 byte <br>
     * signed non-strict:   1 byte
     * @param value Value
     * @param signed True if signed value allowed
     * @param strict True if signed value required
     * @param one True if a return value of 1 is allowed (if false, 1 -> 2)
     * @param three True if a return value of 3 is allowed (if false, 3 -> 4)
     * @return
     */
    public static int getWidth(long value, boolean signed, boolean strict, boolean one, boolean three) {
        if(signed) {
            if(strict) {
                if((value & 0x0_FFFF_FF80l) == 0l || (value & 0x0_FFFF_FF80l) == 0x0_FFFF_FF80l) {
                    return one ? 1 : 2;
                } else if((value & 0x0_FFFF_8000l) == 0l || (value & 0x0_FFFF_8000l) == 0x0_FFFF_8000l) {
                    return 2;
                } else if((value & 0x0_FF80_0000l) == 0l || (value & 0x0_FF80_0000l) == 0x0_FF80_0000l) {
                    return three ? 3 : 4;
                } else {
                    return 4;
                }
            } else {
                if((value & 0x0_FFFF_FF00l) == 0l || (value & 0x0_FFFF_FF80l) == 0x0_FFFF_FF80l) {
                    return one ? 1 : 2;
                } else if((value & 0x0_FFFF_0000l) == 0l || (value & 0x0_FFFF_8000l) == 0x0_FFFF_8000l) {
                    return 2;
                } else if((value & 0x0_FF00_0000l) == 0l || (value & 0x0_FF80_0000l) == 0x0_FF80_0000l) {
                    return three ? 3 : 4;
                } else {
                    return 4;
                }
            }
        } else {
            if((value & 0x0_FFFF_FF00l) == 0l) {
                return one ? 1 : 2;
            } else if((value & 0x0_FFFF_0000l) == 0l) {
                return 2;
            } else if((value & 0x0_FF00_0000l) == 0l) {
                return three ? 3 : 4;
            } else {
                return 4;
            }
        }
    }
    
    /**
     * Add up to bytes bytes from v to list
     * @param list
     * @param v
     * @param bytes 1-4 # of bytes to add
     */
    public static void addBytes(List<Byte> list, long v, int bytes) {
        list.add((byte) v);
        
        if(bytes >= 2) {
            list.add((byte)(v >>> 8));
            
            if(bytes >= 3) {
                list.add((byte)(v >>> 16));
                
                if(bytes >= 4) {
                    list.add((byte)(v >>> 24));
                }
            }
        }
    }
    
    /**
     * i have so many copies of this function lmao
     * @param log
     * @param node
     * @param crossings
     */
    public static void printTree(Logger log, ASTNode node, boolean[] crossings) {
        StringBuilder sb = new StringBuilder();
        
        for(int i = 0; i < crossings.length - 1; i++) {
            sb.append(crossings[i] ? "|  " : "   ");
        }
        
        if(crossings.length > 0) {
            sb.append("+-> ");
        }
        
        if(node != null) {
            sb.append(node.toString());
            log.finest(sb.toString());
            
            for(int i = 0; i != node.getChildren().size(); i++) {
                boolean[] childCrossings = Arrays.copyOf(crossings, crossings.length + 1);
                childCrossings[childCrossings.length - 1] = (i < node.getChildren().size() - 1);
                
                printTree(log, node.getChildren().get(i), childCrossings);
            }
        } else {
            log.finest("null");
        }
    }
    
}
