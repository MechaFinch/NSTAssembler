package notsotiny.nstasm.asmparts;

import notsotiny.sim.Register;

/**
 * An instruction argument
 */
public class ASMArgument {
    
    public static final ASMArgument REG_NONE = new ASMArgument(ASMRegister.NONE, 0),
                                    REG_DA = new ASMArgument(ASMRegister.DA, 0),
                                    REG_BC = new ASMArgument(ASMRegister.BC, 0),
                                    REG_JI = new ASMArgument(ASMRegister.JI, 0),
                                    REG_LK = new ASMArgument(ASMRegister.LK, 0),
                                    REG_XP = new ASMArgument(ASMRegister.XP, 0),
                                    REG_YP = new ASMArgument(ASMRegister.YP, 0),
                                    REG_BP = new ASMArgument(ASMRegister.BP, 0),
                                    REG_SP = new ASMArgument(ASMRegister.SP, 0),
                                    REG_A = new ASMArgument(ASMRegister.A, 0),
                                    REG_B = new ASMArgument(ASMRegister.B, 0),
                                    REG_C = new ASMArgument(ASMRegister.C, 0),
                                    REG_D = new ASMArgument(ASMRegister.D, 0),
                                    REG_I = new ASMArgument(ASMRegister.I, 0),
                                    REG_J = new ASMArgument(ASMRegister.J, 0),
                                    REG_K = new ASMArgument(ASMRegister.K, 0),
                                    REG_L = new ASMArgument(ASMRegister.L, 0),
                                    REG_AH = new ASMArgument(ASMRegister.AH, 0),
                                    REG_AL = new ASMArgument(ASMRegister.AL, 0),
                                    REG_BH = new ASMArgument(ASMRegister.BH, 0),
                                    REG_BL = new ASMArgument(ASMRegister.BL, 0),
                                    REG_CH = new ASMArgument(ASMRegister.CH, 0),
                                    REG_CL = new ASMArgument(ASMRegister.CL, 0),
                                    REG_DH = new ASMArgument(ASMRegister.DH, 0),
                                    REG_DL = new ASMArgument(ASMRegister.DL, 0),
                                    REG_IP = new ASMArgument(ASMRegister.IP, 0),
                                    REG_F = new ASMArgument(ASMRegister.F, 0),
                                    REG_PF = new ASMArgument(ASMRegister.PF, 0),
                                    REG_ISP = new ASMArgument(ASMRegister.ISP, 0);
    
    private int size;
    
    private ASMLocation location;
    
    /**
     * @param location Location specified by the argument
     * @param size Size override sepcified by the argument, <= 0 if not specified
     */
    public ASMArgument(ASMLocation location, int size) {
        this.size = size;
        this.location = location;
    }
    
    /**
     * From register
     * @param r
     * @return
     */
    public static ASMArgument fromReg(Register r) {
        return switch(r) {
            case NONE   -> REG_NONE;
            case DA     -> REG_DA;
            case A      -> REG_A;
            case AH     -> REG_AH;
            case AL     -> REG_AL;
            case B      -> REG_B;
            case BH     -> REG_BH;
            case BL     -> REG_BL;
            case BC     -> REG_BC;
            case C      -> REG_C;
            case CH     -> REG_CH;
            case CL     -> REG_CL;
            case D      -> REG_D;
            case DH     -> REG_DH;
            case DL     -> REG_DL;
            case JI     -> REG_JI;
            case I      -> REG_I;
            case J      -> REG_J;
            case LK     -> REG_LK;
            case K      -> REG_K;
            case L      -> REG_L;
            case XP     -> REG_XP;
            case YP     -> REG_YP;
            case BP     -> REG_BP;
            case SP     -> REG_SP;
            case IP     -> REG_IP;
            case F      -> REG_F;
            case PF     -> REG_PF;
            case ISP    -> REG_ISP;
        };
    }
    
    public int size() { return this.size; }
    public ASMLocation location() { return this.location; }
    
    @Override
    public String toString() {
        return switch(this.size) {
            case 1  -> "byte ";
            case 2  -> "word ";
            case 4  -> "ptr ";
            default -> "";
        } + this.location;
    }
    
}
