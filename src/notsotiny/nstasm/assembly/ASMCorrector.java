package notsotiny.nstasm.assembly;

/**
 * A class which takes the output of the ASMParser and corrects opcodes.
 * e.g. MOV_RIM A, F        -> MOV_RIM_F A, F
 * e.g. ADD_RIM A, byte 5   -> ADD_RIM_I8 A, byte 5
 * ADD_RIM A, 5 would remain with the 16 bit immediate, but may be transformed to ADD_RIM_I8 A, 5 and then ADD_A_I8 A, 5 by size optimization 
 */
public class ASMCorrector {
    // TODO
}
