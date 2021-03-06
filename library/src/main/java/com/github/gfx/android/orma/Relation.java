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
package com.github.gfx.android.orma;

import com.github.gfx.android.orma.annotation.Experimental;
import com.github.gfx.android.orma.annotation.OnConflict;
import com.github.gfx.android.orma.annotation.PrimaryKey;
import com.github.gfx.android.orma.event.DataSetChangedEvent;
import com.github.gfx.android.orma.internal.OrmaConditionBase;

import android.support.annotation.CheckResult;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Maybe;
import io.reactivex.MaybeEmitter;
import io.reactivex.MaybeOnSubscribe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.functions.Function;

/**
 * Representation of a relation, or a {@code SELECT} query.
 *
 * @param <Model> An Orma model
 * @param <R>     The derived class itself. e.g {@code class Foo_Schema extends Relation<Foo, Foo_Schema>}
 */
public abstract class Relation<Model, R extends Relation<Model, ?>> extends OrmaConditionBase<Model, R>
        implements Cloneable, Iterable<Model> {

    final protected ArrayList<OrderSpec<Model>> orderSpecs = new ArrayList<>();

    public Relation(@NonNull OrmaConnection connection) {
        super(connection);
    }

    public Relation(@NonNull Relation<Model, ?> relation) {
        super(relation);
        orderSpecs.addAll(relation.orderSpecs);
    }

    @NonNull
    @Override
    protected String buildColumnName(@NonNull ColumnDef<Model, ?> column) {
        return column.getQualifiedName();
    }

    @SuppressWarnings("unchecked")
    @NonNull
    public R orderBy(@NonNull OrderSpec<Model> orderSpec) {
        orderSpecs.add(orderSpec);
        return (R) this;
    }

    @Nullable
    protected String buildOrderingTerms() {
        if (orderSpecs.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (OrderSpec<Model> orderSpec : orderSpecs) {
            if (sb.length() != 0) {
                sb.append(", ");
            }
            sb.append(orderSpec);
        }
        return sb.toString();
    }

    @IntRange(from = 0)
    public int count() {
        return selector().count();
    }

    public boolean isEmpty() {
        return selector().isEmpty();
    }

    @NonNull
    public Model get(@IntRange(from = 0) int position) {
        return selector().get(position);
    }

    @NonNull
    public Model getOrCreate(@IntRange(from = 0) long position, @NonNull ModelFactory<Model> factory) {
        Model model = selector().getOrNull(position);
        if (model == null) {
            return conn.createModel(getSchema(), factory);
        } else {
            return model;
        }
    }

    @CheckResult
    @NonNull
    public Single<Model> getAsSingle(@IntRange(from = 0) final int position) {
        return Single.fromCallable(new Callable<Model>() {
            @Override
            public Model call() throws Exception {
                return get(position);
            }
        });
    }

    /**
     * Finds the index of the item, assuming an order specified by a set of {@code orderBy*()} methods.
     *
     * @param item The item to find
     * @return The position of the item
     */
    @SuppressWarnings("unchecked")
    public int indexOf(@NonNull Model item) {
        Selector<Model, ?> selector = selector();
        for (OrderSpec<Model> orderSpec : orderSpecs) {
            ColumnDef<Model, ?> column = orderSpec.column;
            if (orderSpec.ordering.equals(OrderSpec.ASC)) {
                selector.where(column, "<", column.getSerialized(item));
            } else {
                selector.where(column, ">", column.getSerialized(item));
            }
        }
        return selector.count();
    }

    /**
     * Deletes a specified model and yields where it was. Suitable to implement {@link android.widget.Adapter}.
     * Operations are executed in a transaction.
     *
     * @param item A model to delete.
     * @return An {@link Maybe} that yields the position of the deleted item if the item is deleted.
     */
    @CheckResult
    @NonNull
    public Maybe<Integer> deleteAsMaybe(@NonNull final Model item) {
        return Maybe.create(new MaybeOnSubscribe<Integer>() {
            @Override
            public void subscribe(MaybeEmitter<Integer> emitter) throws Exception {
                final AtomicInteger positionRef = new AtomicInteger(-1);
                conn.transactionSync(new Runnable() {
                    @Override
                    public void run() {
                        int position = indexOf(item);
                        ColumnDef<Model, ?> pk = getSchema().getPrimaryKey();
                        int deletedRows = deleter()
                                .where(pk, "=", pk.getSerialized(item))
                                .execute();

                        if (deletedRows > 0) {
                            positionRef.set(position);
                        }
                    }
                });
                // emit the position *after* the transaction finished
                if (positionRef.get() >= 0) {
                    emitter.onSuccess(positionRef.get());
                } else {
                    emitter.onComplete();
                }
            }
        });
    }

    /**
     * Truncates the table to the specified size.
     *
     * @param size Size to truncate the table
     * @return A {@link Single} that yields the number of rows deleted.
     */
    @CheckResult
    @NonNull
    public Single<Integer> truncateAsSingle(@IntRange(from = 0) final int size) {
        return Single.fromCallable(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                String pk = getSchema().getPrimaryKey().getEscapedName();
                Selector<Model, ?> subquery = selector();
                subquery.limit(Integer.MAX_VALUE);
                subquery.offset(size);
                return conn.delete(getSchema(), pk + " IN (" + subquery.buildQueryWithColumns(pk) + ")", getBindArgs());
            }
        });
    }

    /**
     * Inserts an item.
     *
     * @param factory A model to insert.
     * @return An {@link Single} that yields the newly inserted row id.
     */
    @CheckResult
    @NonNull
    public Single<Long> insertAsSingle(@NonNull final Callable<Model> factory) {
        return Single.fromCallable(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                return inserter().execute(factory);
            }
        });
    }

    @Override
    public abstract R clone();

    // Operation helpers

    @NonNull
    public abstract Selector<Model, ?> selector();

    @NonNull
    public abstract Updater<Model, ?> updater();

    @NonNull
    public abstract Deleter<Model, ?> deleter();

    @NonNull
    public Inserter<Model> inserter() {
        return inserter(OnConflict.NONE, true);
    }

    @NonNull
    public Inserter<Model> inserter(@OnConflict int onConflictAlgorithm) {
        return new Inserter<>(conn, getSchema(), onConflictAlgorithm, true);
    }

    /**
     * @param onConflictAlgorithm {@link OnConflict} algorithm
     * @param withoutAutoId       If {@code true}, the primary key with {@link PrimaryKey#auto()} is omitted in the {@code
     *                            INSERT} statement.
     * @return An {@link Inserter} instance, or a prepared statement to {@code INSERT}.
     */
    @NonNull
    public Inserter<Model> inserter(@OnConflict int onConflictAlgorithm, boolean withoutAutoId) {
        return new Inserter<>(conn, getSchema(), onConflictAlgorithm, withoutAutoId);
    }

    /**
     * Equivalent to {@code relation.inserter(OnConflict.REPLACE, false)}.
     *
     * @return An {@code Inserter} instance to upsert rows.
     */
    @NonNull
    public Inserter<Model> upserter() {
        return inserter(OnConflict.REPLACE, false);
    }

    /**
     * Experimental API to observe data-set changed events.
     *
     * @param <S> A concrete {@link Selector} class.
     * @return A hot observable that yields {@link Selector} when the target data-set is changed.
     */
    @Experimental
    @SuppressWarnings("unchecked")
    public <S extends Selector<Model, ?>> Observable<S> createQueryObservable() {
        return conn.createEventObservable((S)selector())
                .map(new Function<DataSetChangedEvent<S>, S>() {
                    @Override
                    public S apply(DataSetChangedEvent<S> event) throws Exception {
                        return event.getSelector();
                    }
                });
    }

    /**
     * Experimental API to observe data-set changed events.
     * This is provided to test whether it is useful or not, and not intended to be used in production yet.
     *
     * @param <S> A concrete {@link Selector} class.
     * @return A hot observable that yields {@link Selector} when the target data-set is changed.
     */
    @Experimental
    @Deprecated
    @SuppressWarnings("unchecked")
    public <S extends Selector<Model, ?>> Observable<DataSetChangedEvent<S>> createEventObservable() {
        return conn.createEventObservable((S)selector());
    }

    // Iterator<Model>

    @Override
    public Iterator<Model> iterator() {
        return selector().iterator();
    }
}
