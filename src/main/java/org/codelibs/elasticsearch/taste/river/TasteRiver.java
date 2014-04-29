package org.codelibs.elasticsearch.taste.river;

import java.util.List;
import java.util.Map;

import org.apache.mahout.cf.taste.eval.RecommenderBuilder;
import org.apache.mahout.cf.taste.eval.RecommenderEvaluator;
import org.codelibs.elasticsearch.taste.TasteSystemException;
import org.codelibs.elasticsearch.taste.eval.ItemBasedRecommenderBuilder;
import org.codelibs.elasticsearch.taste.eval.RecommenderEvaluatorFactory;
import org.codelibs.elasticsearch.taste.eval.UserBasedRecommenderBuilder;
import org.codelibs.elasticsearch.taste.model.ElasticsearchDataModel;
import org.codelibs.elasticsearch.taste.model.IndexInfo;
import org.codelibs.elasticsearch.taste.service.TasteService;
import org.codelibs.elasticsearch.taste.writer.RecommendedItemsWriter;
import org.codelibs.elasticsearch.taste.writer.ReportWriter;
import org.codelibs.elasticsearch.taste.writer.SimilarItemsWriter;
import org.codelibs.elasticsearch.util.SettingsUtils;
import org.codelibs.elasticsearch.util.StringUtils;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.mapping.delete.DeleteMappingResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;
import org.elasticsearch.search.Scroll;

public class TasteRiver extends AbstractRiverComponent implements River {
    private static final String EVALUATE_ITEMS_FROM_USER = "evaluate_items_from_user";

    private static final String RECOMMENDED_ITEMS_FROM_ITEM = "recommended_items_from_item";

    private static final String RECOMMENDED_ITEMS_FROM_USER = "recommended_items_from_user";

    private final Client client;

    private TasteService tasteService;

    private Thread riverThread;

    @Inject
    public TasteRiver(final RiverName riverName, final RiverSettings settings,
            final Client client, final TasteService tasteService) {
        super(riverName, settings);
        this.client = client;
        this.tasteService = tasteService;

        logger.info("CREATE TasteRiver");
    }

    @Override
    public void start() {
        waitForClusterStatus();

        logger.info("START TasteRiver");
        try {
            final Map<String, Object> rootSettings = settings.settings();
            final Object actionObj = rootSettings.get("action");
            if (RECOMMENDED_ITEMS_FROM_USER.equals(actionObj)) {
                final int numOfItems = SettingsUtils.get(rootSettings,
                        "num_of_items", 10);
                final int maxDuration = SettingsUtils.get(rootSettings,
                        "max_duration", 0);
                final int degreeOfParallelism = getDegreeOfParallelism();

                final Map<String, Object> indexInfoSettings = SettingsUtils
                        .get(rootSettings, "index_info");
                final IndexInfo indexInfo = new IndexInfo(indexInfoSettings);

                final Map<String, Object> modelInfoSettings = SettingsUtils
                        .get(rootSettings, "data_model");
                final ElasticsearchDataModel dataModel = createDataModel(
                        client, indexInfo, modelInfoSettings);

                waitForClusterStatus(indexInfo.getUserIndex(),
                        indexInfo.getItemIndex(),
                        indexInfo.getPreferenceIndex(),
                        indexInfo.getRecommendationIndex());

                final RecommendedItemsWriter writer = createRecommendedItemsWriter(indexInfo);

                final UserBasedRecommenderBuilder recommenderBuilder = new UserBasedRecommenderBuilder(
                        indexInfo, rootSettings);

                startRiverThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            tasteService.compute(dataModel, recommenderBuilder,
                                    writer, numOfItems, degreeOfParallelism,
                                    maxDuration);
                        } catch (final Exception e) {
                            logger.error("River {} is failed.", e,
                                    riverName.name());
                        } finally {
                            deleteRiver();
                        }
                    }
                }, "River" + riverName.name());
            } else if (RECOMMENDED_ITEMS_FROM_ITEM.equals(actionObj)) {
                final int numOfItems = SettingsUtils.get(rootSettings,
                        "num_of_items", 10);
                final int maxDuration = SettingsUtils.get(rootSettings,
                        "max_duration", 0);
                final int degreeOfParallelism = getDegreeOfParallelism();

                final Map<String, Object> indexInfoSettings = SettingsUtils
                        .get(rootSettings, "index_info");
                final IndexInfo indexInfo = new IndexInfo(indexInfoSettings);

                final Map<String, Object> modelInfoSettings = SettingsUtils
                        .get(rootSettings, "data_model");
                final ElasticsearchDataModel dataModel = createDataModel(
                        client, indexInfo, modelInfoSettings);

                waitForClusterStatus(indexInfo.getUserIndex(),
                        indexInfo.getItemIndex(),
                        indexInfo.getPreferenceIndex(),
                        indexInfo.getItemSimilarityIndex());

                final ItemBasedRecommenderBuilder recommenderBuilder = new ItemBasedRecommenderBuilder(
                        indexInfo, rootSettings);

                final SimilarItemsWriter writer = createSimilarItemsWriter(indexInfo);

                startRiverThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            tasteService.compute(dataModel, recommenderBuilder,
                                    writer, numOfItems, degreeOfParallelism,
                                    maxDuration);
                        } catch (final Exception e) {
                            logger.error("River {} is failed.", e,
                                    riverName.name());
                        } finally {
                            deleteRiver();
                        }
                    }
                }, "River" + riverName.name());
            } else if (EVALUATE_ITEMS_FROM_USER.equals(actionObj)) {

                final Map<String, Object> indexInfoSettings = SettingsUtils
                        .get(rootSettings, "index_info");
                final IndexInfo indexInfo = new IndexInfo(indexInfoSettings);

                final Map<String, Object> modelInfoSettings = SettingsUtils
                        .get(rootSettings, "data_model");
                final ElasticsearchDataModel dataModel = createDataModel(
                        client, indexInfo, modelInfoSettings);

                waitForClusterStatus(indexInfo.getUserIndex(),
                        indexInfo.getItemIndex(),
                        indexInfo.getPreferenceIndex(),
                        indexInfo.getItemSimilarityIndex());

                final RecommenderBuilder recommenderBuilder = new UserBasedRecommenderBuilder(
                        indexInfo, rootSettings);

                final Map<String, Object> evaluatorSettings = SettingsUtils
                        .get(rootSettings, "evaluator");
                createRecommenderEvaluator(evaluatorSettings);

                final ReportWriter writer = createReportWriter(indexInfo);

                startRiverThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            tasteService.evaluate(dataModel,
                                    recommenderBuilder, writer);
                        } catch (final Exception e) {
                            logger.error("River {} is failed.", e,
                                    riverName.name());
                        } finally {
                            deleteRiver();
                        }
                    }
                }, "River" + riverName.name());
            } else {
                logger.info("River {} has no actions. Deleting...",
                        riverName.name());
            }
        } finally {
            if (riverThread == null) {
                deleteRiver();
            }
        }
    }

    protected void waitForClusterStatus(final String... indices) {
        final ClusterHealthResponse response = client.admin().cluster()
                .prepareHealth(indices).setWaitForYellowStatus().execute()
                .actionGet();
        final List<String> failures = response.getAllValidationFailures();
        if (!failures.isEmpty()) {
            throw new TasteSystemException("Cluster is not available: "
                    + failures.toString());
        }
    }

    protected void startRiverThread(final Runnable runnable, final String name) {
        riverThread = new Thread(runnable, name);
        try {
            riverThread.start();
        } catch (final Exception e) {
            logger.error("Failed to start {}.", e, name);
            riverThread = null;
        }
    }

    private int getDegreeOfParallelism() {
        int degreeOfParallelism = Runtime.getRuntime().availableProcessors() - 1;
        if (degreeOfParallelism < 1) {
            degreeOfParallelism = 1;
        }
        return degreeOfParallelism;
    }

    protected RecommendedItemsWriter createRecommendedItemsWriter(
            final IndexInfo indexInfo) {
        final RecommendedItemsWriter writer = new RecommendedItemsWriter(
                client, indexInfo.getRecommendationIndex());
        writer.setType(indexInfo.getRecommendationType());
        writer.setItemIdField(indexInfo.getItemIdField());
        writer.setItemsField(indexInfo.getItemsField());
        writer.setUserIdField(indexInfo.getUserIdField());
        writer.setValueField(indexInfo.getValueField());
        writer.setTimestampField(indexInfo.getTimestampField());

        writer.open();

        return writer;
    }

    protected SimilarItemsWriter createSimilarItemsWriter(
            final IndexInfo indexInfo) {
        final SimilarItemsWriter writer = new SimilarItemsWriter(client,
                indexInfo.getItemSimilarityIndex());
        writer.setType(indexInfo.getItemSimilarityType());
        writer.setItemIdField(indexInfo.getItemIdField());
        writer.setItemsField(indexInfo.getItemsField());
        writer.setValueField(indexInfo.getValueField());
        writer.setTimestampField(indexInfo.getTimestampField());

        writer.open();

        return writer;
    }

    protected ReportWriter createReportWriter(final IndexInfo indexInfo) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void close() {
        logger.info("CLOSE TasteRiver");
    }

    protected void deleteRiver() {
        final DeleteMappingResponse deleteMappingResponse = client.admin()
                .indices().prepareDeleteMapping("_river")
                .setType(riverName.name()).execute().actionGet();
        if (deleteMappingResponse.isAcknowledged()) {
            logger.info("Deleted " + riverName.name() + "river.");
        } else {
            logger.warn("Failed to delete " + riverName.name() + ". Response: "
                    + deleteMappingResponse.toString());
        }
    }

    protected ElasticsearchDataModel createDataModel(final Client client,
            final IndexInfo indexInfo,
            final Map<String, Object> modelInfoSettings) {
        if (StringUtils.isBlank(indexInfo.getUserIndex())) {
            throw new TasteSystemException("User Index is blank.");
        }
        if (StringUtils.isBlank(indexInfo.getPreferenceIndex())) {
            throw new TasteSystemException("Preference Index is blank.");
        }
        if (StringUtils.isBlank(indexInfo.getItemIndex())) {
            throw new TasteSystemException("Item Index is blank.");
        }

        final String className = SettingsUtils.get(modelInfoSettings, "class");
        if (StringUtils.isBlank(className)) {
            throw new TasteSystemException("A class name is blank.");
        }

        try {
            final Class<?> clazz = Class.forName(className);
            final ElasticsearchDataModel model = (ElasticsearchDataModel) clazz
                    .newInstance();
            model.setClient(client);
            model.setPreferenceIndex(indexInfo.getPreferenceIndex());
            model.setPreferenceType(indexInfo.getPreferenceType());
            model.setUserIndex(indexInfo.getUserIndex());
            model.setUserType(indexInfo.getUserType());
            model.setItemIndex(indexInfo.getItemIndex());
            model.setItemType(indexInfo.getItemType());
            model.setUserIdField(indexInfo.getUserIdField());
            model.setItemIdField(indexInfo.getItemIdField());
            model.setValueField(indexInfo.getValueField());
            model.setTimestampField(indexInfo.getTimestampField());

            final Map<String, Object> scrollSettings = SettingsUtils.get(
                    modelInfoSettings, "scroll");
            model.setScrollSize(SettingsUtils.get(scrollSettings, "size", 1000));
            model.setScrollKeepAlive(new Scroll(TimeValue
                    .timeValueSeconds(SettingsUtils.get(scrollSettings,
                            "keep_alive", 60))));

            final Map<String, Object> querySettings = SettingsUtils.get(
                    modelInfoSettings, "query");
            final String userQuery = SettingsUtils.get(querySettings, "user");
            if (StringUtils.isNotBlank(userQuery)) {
                model.setUserQueryBuilder(QueryBuilders.queryString(userQuery));
            }
            final String itemQuery = SettingsUtils.get(querySettings, "item");
            if (StringUtils.isNotBlank(itemQuery)) {
                model.setUserQueryBuilder(QueryBuilders.queryString(itemQuery));
            }

            final Map<String, Object> cacheSettings = SettingsUtils.get(
                    modelInfoSettings, "cache");
            final Object weight = SettingsUtils.get(cacheSettings, "weight");
            if (weight instanceof Number) {
                model.setMaxCacheWeight(((Integer) weight).longValue());
            } else {
                final long weightSize = parseWeight(weight.toString());
                if (weightSize > 0) {
                    model.setMaxCacheWeight(weightSize);
                }
            }
            return model;
        } catch (ClassNotFoundException | InstantiationException
                | IllegalAccessException e) {
            throw new TasteSystemException("Could not create an instance of "
                    + className);
        }
    }

    protected RecommenderEvaluator createRecommenderEvaluator(
            final Map<String, Object> recommenderEvaluatorSettings) {
        final String factoryName = SettingsUtils
                .get(recommenderEvaluatorSettings, "factory",
                        "org.codelibs.elasticsearch.taste.eval.RMSRecommenderEvaluatorFactory");
        try {
            final Class<?> clazz = Class.forName(factoryName);
            final RecommenderEvaluatorFactory recommenderEvaluatorFactory = (RecommenderEvaluatorFactory) clazz
                    .newInstance();
            recommenderEvaluatorFactory.init(recommenderEvaluatorSettings);
            return recommenderEvaluatorFactory.create();
        } catch (ClassNotFoundException | InstantiationException
                | IllegalAccessException e) {
            throw new TasteSystemException("Could not create an instance of "
                    + factoryName, e);
        }
    }

    private long parseWeight(final String value) {
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        try {
            final char lastChar = value.charAt(value.length() - 1);
            if (lastChar == 'g' || lastChar == 'G') {
                return Long.parseLong(value.substring(0, value.length() - 2));
            } else if (lastChar == 'm' || lastChar == 'M') {
                return Long.parseLong(value.substring(0, value.length() - 2));
            } else if (lastChar == 'k' || lastChar == 'K') {
                return Long.parseLong(value.substring(0, value.length() - 2));
            }
            return Long.parseLong(value);
        } catch (final Exception e) {
            logger.warn("Failed to parse a weight: {}", e, value);
        }
        return 0;
    }
}
