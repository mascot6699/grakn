/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.graql.internal.template.macro;

import ai.grakn.exception.GraqlQueryException;
import ai.grakn.graql.macro.Macro;

import java.util.List;

/**
 * <p>
 * Convert the given value into a boolean (true/false). Only accepts one argument.
 *
 * Usage:
 *      {@literal @}boolean(<value>)
 *      {@literal @}boolean("true")
 * </p>
 *
 * @author alexandraorth
 */
public class DoubleMacro implements Macro<Double> {

    private static final int numberArguments = 1;

    @Override
    public Double apply(List<Object> values) {
        if(values.size() != numberArguments){
            throw GraqlQueryException.wrongNumberOfMacroArguments(this, values);
        }
        return Double.parseDouble(values.get(0).toString());
    }

    @Override
    public String name(){
        return "double";
    }
}
