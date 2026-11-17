// Generated from russian.sbl by Snowball 3.2.0 - https://snowballstem.org/

package org.tartarus.snowball.ext;

import org.tartarus.snowball.Among;

/**
 * This class implements the stemming algorithm defined by a snowball script.
 * <p>
 * Generated from russian.sbl by Snowball 3.2.0 - https://snowballstem.org/
 * </p>
 */
@SuppressWarnings("unused")
public class russianStemmer extends org.tartarus.snowball.SnowballStemmer {

    private final static Among[] a_0 = {
        new Among("\u0432", -1, 1),
        new Among("\u0438\u0432", 0, 2),
        new Among("\u044B\u0432", 0, 2),
        new Among("\u0432\u0448\u0438", -1, 1),
        new Among("\u0438\u0432\u0448\u0438", 3, 2),
        new Among("\u044B\u0432\u0448\u0438", 3, 2),
        new Among("\u0432\u0448\u0438\u0441\u044C", -1, 1),
        new Among("\u0438\u0432\u0448\u0438\u0441\u044C", 6, 2),
        new Among("\u044B\u0432\u0448\u0438\u0441\u044C", 6, 2)
    };

    private final static Among[] a_1 = {
        new Among("\u0435\u0435", -1, 1),
        new Among("\u0438\u0435", -1, 1),
        new Among("\u043E\u0435", -1, 1),
        new Among("\u044B\u0435", -1, 1),
        new Among("\u0438\u043C\u0438", -1, 1),
        new Among("\u044B\u043C\u0438", -1, 1),
        new Among("\u0435\u0439", -1, 1),
        new Among("\u0438\u0439", -1, 1),
        new Among("\u043E\u0439", -1, 1),
        new Among("\u044B\u0439", -1, 1),
        new Among("\u0435\u043C", -1, 1),
        new Among("\u0438\u043C", -1, 1),
        new Among("\u043E\u043C", -1, 1),
        new Among("\u044B\u043C", -1, 1),
        new Among("\u0435\u0433\u043E", -1, 1),
        new Among("\u043E\u0433\u043E", -1, 1),
        new Among("\u0435\u043C\u0443", -1, 1),
        new Among("\u043E\u043C\u0443", -1, 1),
        new Among("\u0438\u0445", -1, 1),
        new Among("\u044B\u0445", -1, 1),
        new Among("\u0435\u044E", -1, 1),
        new Among("\u043E\u044E", -1, 1),
        new Among("\u0443\u044E", -1, 1),
        new Among("\u044E\u044E", -1, 1),
        new Among("\u0430\u044F", -1, 1),
        new Among("\u044F\u044F", -1, 1)
    };

    private final static Among[] a_2 = {
        new Among("\u0435\u043C", -1, 1),
        new Among("\u043D\u043D", -1, 1),
        new Among("\u0432\u0448", -1, 1),
        new Among("\u0438\u0432\u0448", 2, 2),
        new Among("\u044B\u0432\u0448", 2, 2),
        new Among("\u0449", -1, 1),
        new Among("\u044E\u0449", 5, 1),
        new Among("\u0443\u044E\u0449", 6, 2)
    };

    private final static Among[] a_3 = {
        new Among("\u0441\u044C", -1, 1),
        new Among("\u0441\u044F", -1, 1)
    };

    private final static Among[] a_4 = {
        new Among("\u043B\u0430", -1, 1),
        new Among("\u0438\u043B\u0430", 0, 2),
        new Among("\u044B\u043B\u0430", 0, 2),
        new Among("\u043D\u0430", -1, 1),
        new Among("\u0435\u043D\u0430", 3, 2),
        new Among("\u0435\u0442\u0435", -1, 1),
        new Among("\u0438\u0442\u0435", -1, 2),
        new Among("\u0439\u0442\u0435", -1, 1),
        new Among("\u0435\u0439\u0442\u0435", 7, 2),
        new Among("\u0443\u0439\u0442\u0435", 7, 2),
        new Among("\u043B\u0438", -1, 1),
        new Among("\u0438\u043B\u0438", 10, 2),
        new Among("\u044B\u043B\u0438", 10, 2),
        new Among("\u0439", -1, 1),
        new Among("\u0435\u0439", 13, 2),
        new Among("\u0443\u0439", 13, 2),
        new Among("\u043B", -1, 1),
        new Among("\u0438\u043B", 16, 2),
        new Among("\u044B\u043B", 16, 2),
        new Among("\u0435\u043C", -1, 1),
        new Among("\u0438\u043C", -1, 2),
        new Among("\u044B\u043C", -1, 2),
        new Among("\u043D", -1, 1),
        new Among("\u0435\u043D", 22, 2),
        new Among("\u043B\u043E", -1, 1),
        new Among("\u0438\u043B\u043E", 24, 2),
        new Among("\u044B\u043B\u043E", 24, 2),
        new Among("\u043D\u043E", -1, 1),
        new Among("\u0435\u043D\u043E", 27, 2),
        new Among("\u043D\u043D\u043E", 27, 1),
        new Among("\u0435\u0442", -1, 1),
        new Among("\u0443\u0435\u0442", 30, 2),
        new Among("\u0438\u0442", -1, 2),
        new Among("\u044B\u0442", -1, 2),
        new Among("\u044E\u0442", -1, 1),
        new Among("\u0443\u044E\u0442", 34, 2),
        new Among("\u044F\u0442", -1, 2),
        new Among("\u043D\u044B", -1, 1),
        new Among("\u0435\u043D\u044B", 37, 2),
        new Among("\u0442\u044C", -1, 1),
        new Among("\u0438\u0442\u044C", 39, 2),
        new Among("\u044B\u0442\u044C", 39, 2),
        new Among("\u0435\u0448\u044C", -1, 1),
        new Among("\u0438\u0448\u044C", -1, 2),
        new Among("\u044E", -1, 2),
        new Among("\u0443\u044E", 44, 2)
    };

    private final static Among[] a_5 = {
        new Among("\u0430", -1, 1),
        new Among("\u0435\u0432", -1, 1),
        new Among("\u043E\u0432", -1, 1),
        new Among("\u0435", -1, 1),
        new Among("\u0438\u0435", 3, 1),
        new Among("\u044C\u0435", 3, 1),
        new Among("\u0438", -1, 1),
        new Among("\u0435\u0438", 6, 1),
        new Among("\u0438\u0438", 6, 1),
        new Among("\u0430\u043C\u0438", 6, 1),
        new Among("\u044F\u043C\u0438", 6, 1),
        new Among("\u0438\u044F\u043C\u0438", 10, 1),
        new Among("\u0439", -1, 1),
        new Among("\u0435\u0439", 12, 1),
        new Among("\u0438\u0435\u0439", 13, 1),
        new Among("\u0438\u0439", 12, 1),
        new Among("\u043E\u0439", 12, 1),
        new Among("\u0430\u043C", -1, 1),
        new Among("\u0435\u043C", -1, 1),
        new Among("\u0438\u0435\u043C", 18, 1),
        new Among("\u043E\u043C", -1, 1),
        new Among("\u044F\u043C", -1, 1),
        new Among("\u0438\u044F\u043C", 21, 1),
        new Among("\u043E", -1, 1),
        new Among("\u0443", -1, 1),
        new Among("\u0430\u0445", -1, 1),
        new Among("\u044F\u0445", -1, 1),
        new Among("\u0438\u044F\u0445", 26, 1),
        new Among("\u044B", -1, 1),
        new Among("\u044C", -1, 1),
        new Among("\u044E", -1, 1),
        new Among("\u0438\u044E", 30, 1),
        new Among("\u044C\u044E", 30, 1),
        new Among("\u044F", -1, 1),
        new Among("\u0438\u044F", 33, 1),
        new Among("\u044C\u044F", 33, 1)
    };

    private final static Among[] a_6 = {
        new Among("\u043E\u0441\u0442", -1, 1),
        new Among("\u043E\u0441\u0442\u044C", -1, 1)
    };

    private final static Among[] a_7 = {
        new Among("\u0435\u0439\u0448\u0435", -1, 1),
        new Among("\u043D", -1, 2),
        new Among("\u0435\u0439\u0448", -1, 1),
        new Among("\u044C", -1, 3)
    };

    private static final char[] g_v = {33, 65, 8, 232 };


    @Override
    public boolean stem() {
        int among_var;
        int I_p2;
        int I_pV;
        int v_1 = cursor;
        lab0: {
            while (true)
            {
                int v_2 = cursor;
                lab1: {
                    golab2: while (true)
                    {
                        int v_3 = cursor;
                        lab3: {
                            bra = cursor;
                            if (!(eq_s("\u0451")))
                            {
                                break lab3;
                            }
                            ket = cursor;
                            cursor = v_3;
                            break golab2;
                        }
                        cursor = v_3;
                        if (cursor >= limit)
                        {
                            break lab1;
                        }
                        cursor++;
                    }
                    slice_from("\u0435");
                    continue;
                }
                cursor = v_2;
                break;
            }
        }
        cursor = v_1;
        lab4: {
            I_pV = limit;
            I_p2 = limit;
            int v_4 = cursor;
            lab5: {
                if (!go_out_grouping(g_v, 1072, 1103))
                {
                    break lab5;
                }
                cursor++;
                I_pV = cursor;
                if (!go_in_grouping(g_v, 1072, 1103))
                {
                    break lab5;
                }
                cursor++;
                if (!go_out_grouping(g_v, 1072, 1103))
                {
                    break lab5;
                }
                cursor++;
                if (!go_in_grouping(g_v, 1072, 1103))
                {
                    break lab5;
                }
                cursor++;
                I_p2 = cursor;
            }
            cursor = v_4;
        }
        limit_backward = cursor;
        cursor = limit;
        if (cursor < I_pV)
        {
            return false;
        }
        int v_5 = limit_backward;
        limit_backward = I_pV;
        int v_6 = limit - cursor;
        lab6: {
            lab7: {
                int v_7 = limit - cursor;
                lab8: {
                    ket = cursor;
                    among_var = find_among_b(a_0);
                    if (among_var == 0)
                    {
                        break lab8;
                    }
                    bra = cursor;
                    switch (among_var) {
                        case 1:
                            lab9: {
                                lab10: {
                                    if (!(eq_s_b("\u0430")))
                                    {
                                        break lab10;
                                    }
                                    break lab9;
                                }
                                if (!(eq_s_b("\u044F")))
                                {
                                    break lab8;
                                }
                            }
                            slice_del();
                            break;
                        case 2:
                            slice_del();
                            break;
                    }
                    break lab7;
                }
                cursor = limit - v_7;
                int v_8 = limit - cursor;
                lab11: {
                    ket = cursor;
                    if (find_among_b(a_3) == 0)
                    {
                        cursor = limit - v_8;
                        break lab11;
                    }
                    bra = cursor;
                    slice_del();
                }
                lab12: {
                    int v_9 = limit - cursor;
                    lab13: {
                        ket = cursor;
                        if (find_among_b(a_1) == 0)
                        {
                            break lab13;
                        }
                        bra = cursor;
                        slice_del();
                        int v_10 = limit - cursor;
                        lab14: {
                            ket = cursor;
                            among_var = find_among_b(a_2);
                            if (among_var == 0)
                            {
                                cursor = limit - v_10;
                                break lab14;
                            }
                            bra = cursor;
                            switch (among_var) {
                                case 1:
                                    lab15: {
                                        lab16: {
                                            if (!(eq_s_b("\u0430")))
                                            {
                                                break lab16;
                                            }
                                            break lab15;
                                        }
                                        if (!(eq_s_b("\u044F")))
                                        {
                                            cursor = limit - v_10;
                                            break lab14;
                                        }
                                    }
                                    slice_del();
                                    break;
                                case 2:
                                    slice_del();
                                    break;
                            }
                        }
                        break lab12;
                    }
                    cursor = limit - v_9;
                    lab17: {
                        ket = cursor;
                        among_var = find_among_b(a_4);
                        if (among_var == 0)
                        {
                            break lab17;
                        }
                        bra = cursor;
                        switch (among_var) {
                            case 1:
                                lab18: {
                                    lab19: {
                                        if (!(eq_s_b("\u0430")))
                                        {
                                            break lab19;
                                        }
                                        break lab18;
                                    }
                                    if (!(eq_s_b("\u044F")))
                                    {
                                        break lab17;
                                    }
                                }
                                slice_del();
                                break;
                            case 2:
                                slice_del();
                                break;
                        }
                        break lab12;
                    }
                    cursor = limit - v_9;
                    ket = cursor;
                    if (find_among_b(a_5) == 0)
                    {
                        break lab6;
                    }
                    bra = cursor;
                    slice_del();
                }
            }
        }
        cursor = limit - v_6;
        int v_11 = limit - cursor;
        lab20: {
            ket = cursor;
            if (!(eq_s_b("\u0438")))
            {
                cursor = limit - v_11;
                break lab20;
            }
            bra = cursor;
            slice_del();
        }
        int v_12 = limit - cursor;
        lab21: {
            ket = cursor;
            if (find_among_b(a_6) == 0)
            {
                break lab21;
            }
            bra = cursor;
            if (I_p2 > cursor)
            {
                break lab21;
            }
            slice_del();
        }
        cursor = limit - v_12;
        int v_13 = limit - cursor;
        lab22: {
            ket = cursor;
            among_var = find_among_b(a_7);
            if (among_var == 0)
            {
                break lab22;
            }
            bra = cursor;
            switch (among_var) {
                case 1:
                    slice_del();
                    ket = cursor;
                    if (!(eq_s_b("\u043D")))
                    {
                        break lab22;
                    }
                    bra = cursor;
                    if (!(eq_s_b("\u043D")))
                    {
                        break lab22;
                    }
                    slice_del();
                    break;
                case 2:
                    if (!(eq_s_b("\u043D")))
                    {
                        break lab22;
                    }
                    slice_del();
                    break;
                case 3:
                    slice_del();
                    break;
            }
        }
        cursor = limit - v_13;
        limit_backward = v_5;
        cursor = limit_backward;
        return true;
    }

    @Override
    public boolean equals( Object o ) {
        return o instanceof russianStemmer;
    }

    @Override
    public int hashCode() {
        return russianStemmer.class.getName().hashCode();
    }

}
