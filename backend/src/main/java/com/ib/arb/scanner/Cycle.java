package com.ib.arb.scanner;

import static com.ib.arb.common.Constants.Direction.BUY;
import static com.ib.arb.common.Constants.Direction.SELL;

public enum Cycle {
    BBS(new String[]{ BUY,  BUY,  SELL }),
    BSS(new String[]{ BUY,  SELL, SELL }),
    BSB(new String[]{ BUY,  SELL, BUY  }),
    SBS(new String[]{ SELL, BUY,  SELL });

    public final String[] dirs;

    Cycle(String[] dirs) { this.dirs = dirs; }
}
