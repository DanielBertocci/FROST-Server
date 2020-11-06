package de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.tables;

import de.fraunhofer.iosb.ilt.frostserver.model.EntityType;
import de.fraunhofer.iosb.ilt.frostserver.model.core.Entity;
import de.fraunhofer.iosb.ilt.frostserver.model.ext.TimeInstant;
import de.fraunhofer.iosb.ilt.frostserver.persistence.IdManager;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.PostgresPersistenceManager;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.factories.EntityFactories;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.relations.RelationManyToMany;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.relations.RelationOneToMany;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.utils.PropertyFieldRegistry;
import de.fraunhofer.iosb.ilt.frostserver.property.EntityPropertyMain;
import de.fraunhofer.iosb.ilt.frostserver.property.NavigationPropertyMain;
import de.fraunhofer.iosb.ilt.frostserver.util.Constants;
import de.fraunhofer.iosb.ilt.frostserver.util.exception.IncompleteEntityException;
import de.fraunhofer.iosb.ilt.frostserver.util.exception.NoSuchEntityException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import org.jooq.DSLContext;
import org.jooq.DataType;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AbstractTableHistLocations<J extends Comparable> extends StaTableAbstract<J, AbstractTableHistLocations<J>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTableHistLocations.class.getName());
    private static final long serialVersionUID = -1457801967;

    private static AbstractTableHistLocations INSTANCE;
    private static DataType INSTANCE_ID_TYPE;

    public static <J extends Comparable> AbstractTableHistLocations<J> getInstance(DataType<J> idType) {
        if (INSTANCE == null) {
            INSTANCE_ID_TYPE = idType;
            INSTANCE = new AbstractTableHistLocations(INSTANCE_ID_TYPE);
            return INSTANCE;
        }
        if (INSTANCE_ID_TYPE.equals(idType)) {
            return INSTANCE;
        }
        return new AbstractTableHistLocations<>(idType);
    }

    /**
     * The column <code>public.HIST_LOCATIONS.TIME</code>.
     */
    public final TableField<Record, OffsetDateTime> time = createField(DSL.name("TIME"), SQLDataType.TIMESTAMPWITHTIMEZONE, this);

    /**
     * The column <code>public.HIST_LOCATIONS.ID</code>.
     */
    public final TableField<Record, J> colId = createField(DSL.name("ID"), getIdType(), this);

    /**
     * The column <code>public.HIST_LOCATIONS.THING_ID</code>.
     */
    public final TableField<Record, J> colThingId = createField(DSL.name("THING_ID"), getIdType(), this);

    /**
     * Create a <code>public.HIST_LOCATIONS</code> table reference
     */
    private AbstractTableHistLocations(DataType<J> idType) {
        super(idType, DSL.name("HIST_LOCATIONS"), null);
    }

    private AbstractTableHistLocations(Name alias, AbstractTableHistLocations<J> aliased) {
        super(aliased.getIdType(), alias, aliased);
    }

    @Override
    public void initRelations() {
        final TableCollection<J> tables = getTables();
        registerRelation(
                new RelationOneToMany<>(this, AbstractTableThings.getInstance(getIdType()), EntityType.THING)
                        .setSourceFieldAccessor(AbstractTableHistLocations::getThingId)
                        .setTargetFieldAccessor(AbstractTableThings::getId)
        );

        registerRelation(
                new RelationManyToMany<>(this, AbstractTableLocationsHistLocations.getInstance(getIdType()), AbstractTableLocations.getInstance(getIdType()), EntityType.LOCATION)
                        .setSourceFieldAcc(AbstractTableHistLocations::getId)
                        .setSourceLinkFieldAcc(AbstractTableLocationsHistLocations::getHistLocationId)
                        .setTargetLinkFieldAcc(AbstractTableLocationsHistLocations::getLocationId)
                        .setTargetFieldAcc(AbstractTableLocations::getId)
        );
    }

    @Override
    public void initProperties(final EntityFactories<J> entityFactories) {
        final IdManager idManager = entityFactories.idManager;
        pfReg.addEntryId(idManager, AbstractTableHistLocations::getId);
        pfReg.addEntry(EntityPropertyMain.TIME, table -> table.time,
                new PropertyFieldRegistry.ConverterTimeInstant<>(EntityPropertyMain.TIME, table -> table.time)
        );
        pfReg.addEntry(NavigationPropertyMain.THING, AbstractTableHistLocations::getThingId, idManager);
        pfReg.addEntry(NavigationPropertyMain.LOCATIONS, AbstractTableHistLocations::getId, idManager);
    }

    @Override
    public boolean insertIntoDatabase(PostgresPersistenceManager<J> pm, Entity histLoc) throws NoSuchEntityException, IncompleteEntityException {
        super.insertIntoDatabase(pm, histLoc);
        EntityFactories<J> entityFactories = pm.getEntityFactories();
        Entity thing = histLoc.getProperty(NavigationPropertyMain.THING);
        J thingId = (J) thing.getId().getValue();
        DSLContext dslContext = pm.getDslContext();
        AbstractTableHistLocations<J> thl = AbstractTableHistLocations.getInstance(getIdType());

        final TimeInstant hlTime = histLoc.getProperty(EntityPropertyMain.TIME);
        OffsetDateTime newTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(hlTime.getDateTime().getMillis()), Constants.UTC);

        // https://github.com/opengeospatial/sensorthings/issues/30
        // Check the time of the latest HistoricalLocation of our thing.
        // If this time is earlier than our time, set the Locations of our Thing to our Locations.
        Record lastHistLocation = dslContext.select(Collections.emptyList())
                .from(thl)
                .where(thl.getThingId().eq(thingId).and(thl.time.gt(newTime)))
                .orderBy(thl.time.desc())
                .limit(1)
                .fetchOne();
        if (lastHistLocation == null) {
            // We are the newest.
            // Unlink old Locations from Thing.
            AbstractTableThingsLocations<J> qtl = AbstractTableThingsLocations.getInstance(getIdType());
            long count = dslContext
                    .delete(qtl)
                    .where(qtl.getThingId().eq(thingId))
                    .execute();
            LOGGER.debug(EntityFactories.UNLINKED_L_FROM_T, count, thingId);

            // Link new locations to Thing.
            for (Entity l : histLoc.getProperty(NavigationPropertyMain.LOCATIONS)) {
                if (l.getId() == null || !entityFactories.entityExists(pm, l)) {
                    throw new NoSuchEntityException("Location with no id.");
                }
                J locationId = (J) l.getId().getValue();

                dslContext.insertInto(qtl)
                        .set(qtl.getThingId(), thingId)
                        .set(qtl.getLocationId(), locationId)
                        .execute();
                LOGGER.debug(EntityFactories.LINKED_L_TO_T, locationId, thingId);
            }
        }

        return true;
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.HISTORICAL_LOCATION;
    }

    @Override
    public TableField<Record, J> getId() {
        return colId;
    }

    public TableField<Record, J> getThingId() {
        return colThingId;
    }

    @Override
    public AbstractTableHistLocations<J> as(Name alias) {
        return new AbstractTableHistLocations<>(alias, this);
    }

    @Override
    public AbstractTableHistLocations<J> as(String alias) {
        return new AbstractTableHistLocations<>(DSL.name(alias), this);
    }

    @Override
    public AbstractTableHistLocations<J> getThis() {
        return this;
    }

}
