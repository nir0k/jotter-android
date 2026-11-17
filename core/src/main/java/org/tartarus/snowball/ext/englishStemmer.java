// Generated from english.sbl by Snowball 3.2.0 - https://snowballstem.org/

package org.tartarus.snowball.ext;

import org.tartarus.snowball.Among;

/**
 * This class implements the stemming algorithm defined by a snowball script.
 * <p>
 * Generated from english.sbl by Snowball 3.2.0 - https://snowballstem.org/
 * </p>
 */
@SuppressWarnings("unused")
public class englishStemmer extends org.tartarus.snowball.SnowballStemmer {

    private final static Among[] a_0 = {
        new Among("arsen", -1, -1),
        new Among("commun", -1, -1),
        new Among("emerg", -1, -1),
        new Among("gener", -1, -1),
        new Among("inter", -1, -1),
        new Among("later", -1, -1),
        new Among("organ", -1, -1),
        new Among("past", -1, -1),
        new Among("univers", -1, -1)
    };

    private final static Among[] a_1 = {
        new Among("'", -1, 1),
        new Among("'s'", 0, 1),
        new Among("'s", -1, 1)
    };

    private final static Among[] a_2 = {
        new Among("ied", -1, 2),
        new Among("s", -1, 3),
        new Among("ies", 1, 2),
        new Among("sses", 1, 1),
        new Among("ss", 1, -1),
        new Among("us", 1, -1)
    };

    private final static Among[] a_3 = {
        new Among("succ", -1, 1),
        new Among("proc", -1, 1),
        new Among("exc", -1, 1)
    };

    private final static Among[] a_4 = {
        new Among("even", -1, 2),
        new Among("cann", -1, 2),
        new Among("inn", -1, 2),
        new Among("earr", -1, 2),
        new Among("herr", -1, 2),
        new Among("out", -1, 2),
        new Among("y", -1, 1)
    };

    private final static Among[] a_5 = {
        new Among("", -1, -1),
        new Among("ed", 0, 2),
        new Among("eed", 1, 1),
        new Among("ing", 0, 3),
        new Among("edly", 0, 2),
        new Among("eedly", 4, 1),
        new Among("ingly", 0, 2)
    };

    private final static Among[] a_6 = {
        new Among("", -1, 3),
        new Among("bb", 0, 2),
        new Among("dd", 0, 2),
        new Among("ff", 0, 2),
        new Among("gg", 0, 2),
        new Among("bl", 0, 1),
        new Among("mm", 0, 2),
        new Among("nn", 0, 2),
        new Among("pp", 0, 2),
        new Among("rr", 0, 2),
        new Among("at", 0, 1),
        new Among("tt", 0, 2),
        new Among("iz", 0, 1)
    };

    private final static Among[] a_7 = {
        new Among("anci", -1, 3),
        new Among("enci", -1, 2),
        new Among("ogi", -1, 14),
        new Among("li", -1, 16),
        new Among("bli", 3, 12),
        new Among("abli", 4, 4),
        new Among("alli", 3, 8),
        new Among("fulli", 3, 9),
        new Among("lessli", 3, 15),
        new Among("ousli", 3, 10),
        new Among("entli", 3, 5),
        new Among("aliti", -1, 8),
        new Among("biliti", -1, 12),
        new Among("iviti", -1, 11),
        new Among("tional", -1, 1),
        new Among("ational", 14, 7),
        new Among("alism", -1, 8),
        new Among("ation", -1, 7),
        new Among("ization", 17, 6),
        new Among("izer", -1, 6),
        new Among("ator", -1, 7),
        new Among("iveness", -1, 11),
        new Among("fulness", -1, 9),
        new Among("ousness", -1, 10),
        new Among("ogist", -1, 13)
    };

    private final static Among[] a_8 = {
        new Among("icate", -1, 4),
        new Among("ative", -1, 6),
        new Among("alize", -1, 3),
        new Among("iciti", -1, 4),
        new Among("ical", -1, 4),
        new Among("tional", -1, 1),
        new Among("ational", 5, 2),
        new Among("ful", -1, 5),
        new Among("ness", -1, 5)
    };

    private final static Among[] a_9 = {
        new Among("ic", -1, 1),
        new Among("ance", -1, 1),
        new Among("ence", -1, 1),
        new Among("able", -1, 1),
        new Among("ible", -1, 1),
        new Among("ate", -1, 1),
        new Among("ive", -1, 1),
        new Among("ize", -1, 1),
        new Among("iti", -1, 1),
        new Among("al", -1, 1),
        new Among("ism", -1, 1),
        new Among("ion", -1, 2),
        new Among("er", -1, 1),
        new Among("ous", -1, 1),
        new Among("ant", -1, 1),
        new Among("ent", -1, 1),
        new Among("ment", 15, 1),
        new Among("ement", 16, 1)
    };

    private final static Among[] a_10 = {
        new Among("e", -1, 1),
        new Among("l", -1, 2)
    };

    private final static Among[] a_11 = {
        new Among("andes", -1, -1),
        new Among("atlas", -1, -1),
        new Among("bias", -1, -1),
        new Among("cosmos", -1, -1),
        new Among("early", -1, 6),
        new Among("gently", -1, 4),
        new Among("howe", -1, -1),
        new Among("idly", -1, 3),
        new Among("news", -1, -1),
        new Among("only", -1, 7),
        new Among("singly", -1, 8),
        new Among("skies", -1, 2),
        new Among("skis", -1, 1),
        new Among("sky", -1, -1),
        new Among("ugly", -1, 5)
    };

    private static final char[] g_aeo = {17, 64 };

    private static final char[] g_v = {17, 65, 16, 1 };

    private static final char[] g_v_WXY = {1, 17, 65, 208, 1 };

    private static final char[] g_valid_LI = {55, 141, 2 };


    private boolean r_shortv() {
        lab0: {
            int v_1 = limit - cursor;
            lab1: {
                if (!(out_grouping_b(g_v_WXY, 89, 121)))
                {
                    break lab1;
                }
                if (!(in_grouping_b(g_v, 97, 121)))
                {
                    break lab1;
                }
                if (!(out_grouping_b(g_v, 97, 121)))
                {
                    break lab1;
                }
                break lab0;
            }
            cursor = limit - v_1;
            lab2: {
                if (!(out_grouping_b(g_v, 97, 121)))
                {
                    break lab2;
                }
                if (!(in_grouping_b(g_v, 97, 121)))
                {
                    break lab2;
                }
                if (cursor > limit_backward)
                {
                    break lab2;
                }
                break lab0;
            }
            cursor = limit - v_1;
            if (!(eq_s_b("past")))
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean stem() {
        int among_var;
        boolean B_Y_found;
        int I_p2;
        int I_p1;
        lab0: {
            int v_1 = cursor;
            lab1: {
                bra = cursor;
                among_var = find_among(a_11);
                if (among_var == 0)
                {
                    break lab1;
                }
                ket = cursor;
                if (cursor < limit)
                {
                    break lab1;
                }
                switch (among_var) {
                    case 1:
                        slice_from("ski");
                        break;
                    case 2:
                        slice_from("sky");
                        break;
                    case 3:
                        slice_from("idl");
                        break;
                    case 4:
                        slice_from("gentl");
                        break;
                    case 5:
                        slice_from("ugli");
                        break;
                    case 6:
                        slice_from("earli");
                        break;
                    case 7:
                        slice_from("onli");
                        break;
                    case 8:
                        slice_from("singl");
                        break;
                }
                break lab0;
            }
            cursor = v_1;
            lab2: {
                lab3: {
                    {
                        int c = cursor + 3;
                        if (c > limit)
                        {
                            break lab3;
                        }
                        cursor = c;
                    }
                    break lab2;
                }
                break lab0;
            }
            cursor = v_1;
            lab4: {
                B_Y_found = false;
                int v_2 = cursor;
                lab5: {
                    bra = cursor;
                    if (!(eq_s("'")))
                    {
                        break lab5;
                    }
                    ket = cursor;
                    slice_del();
                }
                cursor = v_2;
                int v_3 = cursor;
                lab6: {
                    bra = cursor;
                    if (!(eq_s("y")))
                    {
                        break lab6;
                    }
                    ket = cursor;
                    slice_from("Y");
                    B_Y_found = true;
                }
                cursor = v_3;
                int v_4 = cursor;
                lab7: {
                    while (true)
                    {
                        int v_5 = cursor;
                        lab8: {
                            golab9: while (true)
                            {
                                int v_6 = cursor;
                                lab10: {
                                    if (!(in_grouping(g_v, 97, 121)))
                                    {
                                        break lab10;
                                    }
                                    bra = cursor;
                                    if (!(eq_s("y")))
                                    {
                                        break lab10;
                                    }
                                    ket = cursor;
                                    cursor = v_6;
                                    break golab9;
                                }
                                cursor = v_6;
                                if (cursor >= limit)
                                {
                                    break lab8;
                                }
                                cursor++;
                            }
                            slice_from("Y");
                            B_Y_found = true;
                            continue;
                        }
                        cursor = v_5;
                        break;
                    }
                }
                cursor = v_4;
            }
            lab11: {
                I_p1 = limit;
                I_p2 = limit;
                int v_7 = cursor;
                lab12: {
                    lab13: {
                        int v_8 = cursor;
                        lab14: {
                            if (find_among(a_0) == 0)
                            {
                                break lab14;
                            }
                            break lab13;
                        }
                        cursor = v_8;
                        if (!go_out_grouping(g_v, 97, 121))
                        {
                            break lab12;
                        }
                        cursor++;
                        if (!go_in_grouping(g_v, 97, 121))
                        {
                            break lab12;
                        }
                        cursor++;
                    }
                    I_p1 = cursor;
                    if (!go_out_grouping(g_v, 97, 121))
                    {
                        break lab12;
                    }
                    cursor++;
                    if (!go_in_grouping(g_v, 97, 121))
                    {
                        break lab12;
                    }
                    cursor++;
                    I_p2 = cursor;
                }
                cursor = v_7;
            }
            limit_backward = cursor;
            cursor = limit;
            int v_9 = limit - cursor;
            lab15: {
                int v_10 = limit - cursor;
                lab16: {
                    ket = cursor;
                    if (find_among_b(a_1) == 0)
                    {
                        cursor = limit - v_10;
                        break lab16;
                    }
                    bra = cursor;
                    slice_del();
                }
                ket = cursor;
                among_var = find_among_b(a_2);
                if (among_var == 0)
                {
                    break lab15;
                }
                bra = cursor;
                switch (among_var) {
                    case 1:
                        slice_from("ss");
                        break;
                    case 2:
                        lab17: {
                            int v_11 = limit - cursor;
                            lab18: {
                                {
                                    int c = cursor - 2;
                                    if (c < limit_backward)
                                    {
                                        break lab18;
                                    }
                                    cursor = c;
                                }
                                slice_from("i");
                                break lab17;
                            }
                            cursor = limit - v_11;
                            slice_from("ie");
                        }
                        break;
                    case 3:
                        if (cursor <= limit_backward)
                        {
                            break lab15;
                        }
                        cursor--;
                        if (!go_out_grouping_b(g_v, 97, 121))
                        {
                            break lab15;
                        }
                        cursor--;
                        slice_del();
                        break;
                }
            }
            cursor = limit - v_9;
            int v_12 = limit - cursor;
            lab19: {
                ket = cursor;
                among_var = find_among_b(a_5);
                bra = cursor;
                lab20: {
                    int v_13 = limit - cursor;
                    lab21: {
                        switch (among_var) {
                            case 1:
                                int v_14 = limit - cursor;
                                lab22: {
                                    if (I_p1 > cursor)
                                    {
                                        break lab22;
                                    }
                                    lab23: {
                                        int v_15 = limit - cursor;
                                        lab24: {
                                            if (find_among_b(a_3) == 0)
                                            {
                                                break lab24;
                                            }
                                            if (cursor > limit_backward)
                                            {
                                                break lab24;
                                            }
                                            break lab23;
                                        }
                                        cursor = limit - v_15;
                                        slice_from("ee");
                                    }
                                }
                                cursor = limit - v_14;
                                break;
                            case 2:
                                break lab21;
                            case 3:
                                among_var = find_among_b(a_4);
                                if (among_var == 0)
                                {
                                    break lab21;
                                }
                                switch (among_var) {
                                    case 1:
                                        int v_16 = limit - cursor;
                                        if (!(out_grouping_b(g_v, 97, 121)))
                                        {
                                            break lab21;
                                        }
                                        if (cursor > limit_backward)
                                        {
                                            break lab21;
                                        }
                                        cursor = limit - v_16;
                                        bra = cursor;
                                        slice_from("ie");
                                        break;
                                    case 2:
                                        if (cursor > limit_backward)
                                        {
                                            break lab21;
                                        }
                                        break;
                                }
                                break;
                        }
                        break lab20;
                    }
                    cursor = limit - v_13;
                    int v_17 = limit - cursor;
                    if (!go_out_grouping_b(g_v, 97, 121))
                    {
                        break lab19;
                    }
                    cursor--;
                    cursor = limit - v_17;
                    slice_del();
                    ket = cursor;
                    bra = cursor;
                    int v_18 = limit - cursor;
                    among_var = find_among_b(a_6);
                    switch (among_var) {
                        case 1:
                            slice_from("e");
                            break lab19;
                        case 2:
                            {
                                int v_19 = limit - cursor;
                                lab25: {
                                    if (!(in_grouping_b(g_aeo, 97, 111)))
                                    {
                                        break lab25;
                                    }
                                    if (cursor > limit_backward)
                                    {
                                        break lab25;
                                    }
                                    break lab19;
                                }
                                cursor = limit - v_19;
                            }
                            break;
                        case 3:
                            if (cursor != I_p1)
                            {
                                break lab19;
                            }
                            int v_20 = limit - cursor;
                            if (!r_shortv())
                            {
                                break lab19;
                            }
                            cursor = limit - v_20;
                            slice_from("e");
                            break lab19;
                    }
                    cursor = limit - v_18;
                    ket = cursor;
                    if (cursor <= limit_backward)
                    {
                        break lab19;
                    }
                    cursor--;
                    bra = cursor;
                    slice_del();
                }
            }
            cursor = limit - v_12;
            int v_21 = limit - cursor;
            lab26: {
                ket = cursor;
                lab27: {
                    lab28: {
                        if (!(eq_s_b("y")))
                        {
                            break lab28;
                        }
                        break lab27;
                    }
                    if (!(eq_s_b("Y")))
                    {
                        break lab26;
                    }
                }
                bra = cursor;
                if (!(out_grouping_b(g_v, 97, 121)))
                {
                    break lab26;
                }
                if (cursor <= limit_backward)
                {
                    break lab26;
                }
                slice_from("i");
            }
            cursor = limit - v_21;
            int v_22 = limit - cursor;
            lab29: {
                ket = cursor;
                among_var = find_among_b(a_7);
                if (among_var == 0)
                {
                    break lab29;
                }
                bra = cursor;
                if (I_p1 > cursor)
                {
                    break lab29;
                }
                switch (among_var) {
                    case 1:
                        slice_from("tion");
                        break;
                    case 2:
                        slice_from("ence");
                        break;
                    case 3:
                        slice_from("ance");
                        break;
                    case 4:
                        slice_from("able");
                        break;
                    case 5:
                        slice_from("ent");
                        break;
                    case 6:
                        slice_from("ize");
                        break;
                    case 7:
                        slice_from("ate");
                        break;
                    case 8:
                        slice_from("al");
                        break;
                    case 9:
                        slice_from("ful");
                        break;
                    case 10:
                        slice_from("ous");
                        break;
                    case 11:
                        slice_from("ive");
                        break;
                    case 12:
                        slice_from("ble");
                        break;
                    case 13:
                        slice_from("og");
                        break;
                    case 14:
                        if (!(eq_s_b("l")))
                        {
                            break lab29;
                        }
                        slice_from("og");
                        break;
                    case 15:
                        slice_from("less");
                        break;
                    case 16:
                        if (!(in_grouping_b(g_valid_LI, 99, 116)))
                        {
                            break lab29;
                        }
                        slice_del();
                        break;
                }
            }
            cursor = limit - v_22;
            int v_23 = limit - cursor;
            lab30: {
                ket = cursor;
                among_var = find_among_b(a_8);
                if (among_var == 0)
                {
                    break lab30;
                }
                bra = cursor;
                if (I_p1 > cursor)
                {
                    break lab30;
                }
                switch (among_var) {
                    case 1:
                        slice_from("tion");
                        break;
                    case 2:
                        slice_from("ate");
                        break;
                    case 3:
                        slice_from("al");
                        break;
                    case 4:
                        slice_from("ic");
                        break;
                    case 5:
                        slice_del();
                        break;
                    case 6:
                        if (I_p2 > cursor)
                        {
                            break lab30;
                        }
                        slice_del();
                        break;
                }
            }
            cursor = limit - v_23;
            int v_24 = limit - cursor;
            lab31: {
                ket = cursor;
                among_var = find_among_b(a_9);
                if (among_var == 0)
                {
                    break lab31;
                }
                bra = cursor;
                if (I_p2 > cursor)
                {
                    break lab31;
                }
                switch (among_var) {
                    case 1:
                        slice_del();
                        break;
                    case 2:
                        lab32: {
                            lab33: {
                                if (!(eq_s_b("s")))
                                {
                                    break lab33;
                                }
                                break lab32;
                            }
                            if (!(eq_s_b("t")))
                            {
                                break lab31;
                            }
                        }
                        slice_del();
                        break;
                }
            }
            cursor = limit - v_24;
            int v_25 = limit - cursor;
            lab34: {
                ket = cursor;
                among_var = find_among_b(a_10);
                if (among_var == 0)
                {
                    break lab34;
                }
                bra = cursor;
                switch (among_var) {
                    case 1:
                        lab35: {
                            lab36: {
                                if (I_p2 > cursor)
                                {
                                    break lab36;
                                }
                                break lab35;
                            }
                            if (I_p1 > cursor)
                            {
                                break lab34;
                            }
                            {
                                int v_26 = limit - cursor;
                                lab37: {
                                    if (!r_shortv())
                                    {
                                        break lab37;
                                    }
                                    break lab34;
                                }
                                cursor = limit - v_26;
                            }
                        }
                        slice_del();
                        break;
                    case 2:
                        if (I_p2 > cursor)
                        {
                            break lab34;
                        }
                        if (!(eq_s_b("l")))
                        {
                            break lab34;
                        }
                        slice_del();
                        break;
                }
            }
            cursor = limit - v_25;
            cursor = limit_backward;
            int v_27 = cursor;
            lab38: {
                if (!B_Y_found)
                {
                    break lab38;
                }
                while (true)
                {
                    int v_28 = cursor;
                    lab39: {
                        golab40: while (true)
                        {
                            int v_29 = cursor;
                            lab41: {
                                bra = cursor;
                                if (!(eq_s("Y")))
                                {
                                    break lab41;
                                }
                                ket = cursor;
                                cursor = v_29;
                                break golab40;
                            }
                            cursor = v_29;
                            if (cursor >= limit)
                            {
                                break lab39;
                            }
                            cursor++;
                        }
                        slice_from("y");
                        continue;
                    }
                    cursor = v_28;
                    break;
                }
            }
            cursor = v_27;
        }
        return true;
    }

    @Override
    public boolean equals( Object o ) {
        return o instanceof englishStemmer;
    }

    @Override
    public int hashCode() {
        return englishStemmer.class.getName().hashCode();
    }

}
