package org.elasticsearch.mapping;

import java.beans.IntrospectionException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import jakarta.annotation.Resource;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import com.google.common.collect.Maps;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.mapping.model.Address;
import org.elasticsearch.mapping.model.Person;
import org.elasticsearch.search.sort.SortBuilders;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:elasticsearch-test-context.xml")
public class ElasticSearchInsertMappingTest {
    @Resource
    private ElasticSearchClient esClient;
    @Resource
    private MappingBuilder mappingBuilder;
    @Resource
    private QueryHelper queryHelper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Before
    public void setUp() throws Exception {
/***************
      try {
         Settings settings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), "target/eshome")
                                                .put("transport.type", MockTcpTransportPlugin.MOCK_TCP_TRANSPORT_NAME)
                                                .put(NetworkModule.HTTP_ENABLED.getKey(), false)
                                                .build();
         ArrayList<Class<? extends Plugin>> plugins = new ArrayList<Class<? extends Plugin>>();
         plugins.add (MockTcpTransportPlugin.class);
         MockNode node = new MockNode(settings, plugins);
         Client client = null;
         node.start();
         client = node.client();
         esClient.setClient(client);
      } catch (Exception e) {
         System.out.println ("Got " + e.getMessage());
         e.printStackTrace();
      }
***********************/
    }

    @Test
    public void testMappingAndInsert() throws Exception {

        String indexName = Person.class.getSimpleName().toLowerCase();
        mappingBuilder.initialize("org.elasticsearch.mapping.model");
		try {
        	initIndexes(indexName, new Class[] { Person.class });

        esClient.waitForGreenStatus(indexName);

        Person person = new Person();
        person.setId("personId");
        person.setFirstname("firstname");
        person.setLastname("lastname");
        Address address = new Address();
        address.setCity("Fontainebleau");
        person.setAddress(address);
        save(indexName, person);

        person.setId("AnotherPersonId");
        person.setLastname("something else");
        address.setCity("Paris");
        save(indexName, person);

        esClient.getClient().admin().indices().prepareRefresh(indexName).execute().actionGet();

        Assert.assertNotNull(readById(indexName, "personId"));

        SearchResponse response = search(indexName, "first");
        Assert.assertEquals(0, response.getHits().getTotalHits());
        Assert.assertEquals(0, response.getHits().getHits().length);

        String[] searchIndexes = new String[] { indexName };
        Class<?>[] requestedTypes = new Class[] { Person.class };

        Map<String, String[]> filters = Maps.newHashMap();

        response = this.queryHelper.buildQuery().types(requestedTypes).filters(filters).prepareSearch(searchIndexes).execute(0, 10000);
        Assert.assertEquals(2, response.getHits().getTotalHits());

        filters.put("lastname", new String[] { "lastname" });
        response = this.queryHelper.buildQuery().types(requestedTypes).filters(filters).prepareSearch(searchIndexes).execute(0, 10000);
        Assert.assertEquals(1, response.getHits().getTotalHits());

        filters = Maps.newHashMap();
        filters.put("address.city", new String[] { "Paris" });
        response = this.queryHelper.buildQuery().types(requestedTypes).filters(filters).prepareSearch(searchIndexes).execute(0, 10000);
        Assert.assertEquals(1, response.getHits().getTotalHits());
        filters.put("address.city", new String[] { "Issy" });
        response = this.queryHelper.buildQuery().types(requestedTypes).filters(filters).prepareSearch(searchIndexes).execute(0, 10000);
        Assert.assertEquals(0, response.getHits().getTotalHits());
        filters.put("address.city", new String[] { "Fontainebleau" });
        response = this.queryHelper.buildQuery().types(requestedTypes).filters(filters).prepareSearch(searchIndexes).execute(0, 10000);
        Assert.assertEquals(1, response.getHits().getTotalHits());
		} catch (Exception e) {
			System.out.println ("Got e" + e.getMessage());
			e.printStackTrace();
			throw e;
		}
    }

    public void initIndexes(String indexName, Class<?>[] classes) throws Exception {
        // check if existing before
        final ActionFuture<IndicesExistsResponse> indexExistFuture = esClient.getClient().admin().indices().exists(new IndicesExistsRequest(indexName));
        IndicesExistsResponse response;
        response = indexExistFuture.get();

        if (!response.isExists()) {
            // create the index and add the mapping
            CreateIndexRequestBuilder createIndexRequestBuilder = esClient.getClient().admin().indices().prepareCreate(indexName);

            for (Class<?> clazz : classes) {
                System.out.println(mappingBuilder.getMapping(clazz));
                createIndexRequestBuilder.addMapping("_doc", mappingBuilder.getMapping(clazz), XContentType.JSON);
                //createIndexRequestBuilder.addMapping(clazz.getSimpleName().toLowerCase(), mappingBuilder.getMapping(clazz), XContentType.JSON);
            }
            final CreateIndexResponse createResponse = createIndexRequestBuilder.execute().actionGet();
            if (!createResponse.isAcknowledged()) {
                throw new Exception("Failed to create index <" + indexName + ">");
            }
        }
    }

    public void save(String indexName, Person data) throws JsonGenerationException, JsonMappingException, IOException, IllegalAccessException, 
														IntrospectionException, InvocationTargetException, NoSuchMethodException  {
        String json = jsonMapper.writeValueAsString(data);
		System.out.println (json);
        //esClient.getClient().prepareIndex(indexName, indexName).setOperationThreaded(false).setSource(json).setRefresh(true).execute().actionGet();
		String idValue = (new FieldsMappingBuilder()).getIdValue(data);
        esClient.getClient().prepareIndex(indexName, "_doc", idValue).setSource(json, XContentType.JSON).setRefreshPolicy(RefreshPolicy.IMMEDIATE).execute().actionGet();
    }

    public Person readById(String indexName, String id) throws JsonParseException, JsonMappingException, IOException {
        GetResponse response = esClient.getClient().prepareGet(indexName, "_doc", id).execute().actionGet();

        if (response == null || !response.isExists()) {
            return null;
        }

        return jsonMapper.readValue(response.getSourceAsString(), Person.class);
    }

    public SearchResponse search(String indexName, String searchText) {
        SearchRequestBuilder searchRequestBuilder = esClient.getClient().prepareSearch(indexName);
        searchRequestBuilder.setTypes(Person.class.getSimpleName());

        QueryBuilder queryBuilder;
        queryBuilder = QueryBuilders.matchPhrasePrefixQuery("all", searchText).maxExpansions(10);

        searchRequestBuilder.setSearchType(SearchType.QUERY_THEN_FETCH).setQuery(queryBuilder).setSize(10).setFrom(0);

        searchRequestBuilder.addSort(SortBuilders.scoreSort());

        return searchRequestBuilder.execute().actionGet();
    }
}
