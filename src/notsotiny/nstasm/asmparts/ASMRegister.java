package notsotiny.nstasm.asmparts;

import notsotiny.sim.Register;

/**
 * Container for a Register
 */
public enum ASMRegister implements ASMLocation {
    NONE(Register.NONE),
    DA(Register.DA),
    A(Register.A),
    AH(Register.AH),
    AL(Register.AL),
    B(Register.B),
    BH(Register.BH),
    BL(Register.BL),
    BC(Register.BC),
    C(Register.C),
    CH(Register.CH),
    CL(Register.CL),
    D(Register.D),
    DH(Register.DH),
    DL(Register.DL),
    JI(Register.JI),
    I(Register.I),
    J(Register.J),
    LK(Register.LK),
    K(Register.K),
    L(Register.L),
    XP(Register.XP),
    YP(Register.YP),
    BP(Register.BP),
    SP(Register.SP),
    IP(Register.IP),
    F(Register.F),
    PF(Register.PF),
    ISP(Register.ISP);
    
    private Register r;
    
    /**
     * From register
     * @param r
     */
    private ASMRegister(Register r) {
        this.r = r;
    }
    
    /**
     * From register
     * @param r
     */
    public static ASMRegister fromReg(Register r) {
        return switch(r) {
            case NONE   -> NONE;
            case DA     -> DA;
            case A      -> A;
            case AH     -> AH;
            case AL     -> AL;
            case B      -> B;
            case BH     -> BH;
            case BL     -> BL;
            case BC     -> BC;
            case C      -> C;
            case CH     -> CH;
            case CL     -> CL;
            case D      -> D;
            case DH     -> DH;
            case DL     -> DL;
            case JI     -> JI;
            case I      -> I;
            case J      -> J;
            case LK     -> LK;
            case K      -> K;
            case L      -> L;
            case XP     -> XP;
            case YP     -> YP;
            case BP     -> BP;
            case SP     -> SP;
            case IP     -> IP;
            case F      -> F;
            case PF     -> PF;
            case ISP    -> ISP;
        };
    }
    
    /**
     * From name
     * @param rs
     */
    public static ASMRegister fromString(String rs) {
        return fromReg(Register.fromString(rs));
    }
    
    public Register reg() { return this.r; }
    
    @Override
    public String toString() {
        return this.r.toString();
    }
    
}
