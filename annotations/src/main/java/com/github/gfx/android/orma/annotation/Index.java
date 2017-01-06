/*
 * Copyright (c) 2015 FUJI Goro (gfx).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.gfx.android.orma.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * To create indexes for multiple columns.
 *
 * @see <a href="https://sqlite.org/lang_createindex.html">CREATE INDEX</a>
 */
@Target({ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.CLASS)
public @interface Index {

    String[] value();

    boolean unique() default false;

    String name() default ""; // default: index_${value().join("_")}_on_${table}

    /**
     *
     * @return Bit flags that indicates which helpers to generate
     * @see Column#helpers()
     */
    @Column.Helpers long helpers() default Column.Helpers.AUTO;
}
