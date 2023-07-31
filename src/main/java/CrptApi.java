import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Serializable;

import java.io.UnsupportedEncodingException;
import java.sql.Time;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

enum DOCUMENT_FORMAT {
    MANUAL, XML, CSV;
}

class Document implements Serializable {
    private final String document_format;
    private final String product_document;

    private final String product_group;

    public Document(String document_format, String product_document) {

        this.document_format = document_format;
        this.product_document = product_document;
        this.product_group = null; // в API сказано что это поле не обязательное
    }

    public Document(String document_format, String product_document, String product_group) {
        this.document_format = document_format;
        this.product_document = product_document;
        this.product_group = product_group;
    }

    public String getDocument_format() {
        return document_format;
    }

    public String getProduct_document() {
        return product_document;
    }

    public String getProduct_group() {
        return product_group;
    }

    public JSONObject getJsonDocument() {
        JSONObject jsonDocument = new JSONObject();
        //В описании API сказано что document_format должен быть значением из списка MANUAL,XML,CSV
        try {
            DOCUMENT_FORMAT.valueOf(document_format);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            System.out.println("product_document должен быть значением из списка MANUAL,XML,CSV0");
        }

        jsonDocument.put("document_format", document_format);

        jsonDocument.put("product_document", product_document);

        // В описании API сказано что product_group это число от 1 до 10
        // соотвественно если это не так то стоит кинуть исключение
        // но это поле не обязательное так что добавим проверку на null;
        if (product_group != null) {
            try {
                int pg = Integer.parseInt(product_group);
                if (pg < 1 || pg > 10) {
                    throw new IllegalArgumentException();
                }

            } catch (
                    IllegalArgumentException e) { // parseInt может кинуть NumberFormatException но он наследуется от IllegalArgumentException так что мы и его тоже поймаем
                e.printStackTrace();
                System.out.println("product_document должен быть числом от 1 до 10");
            }
            jsonDocument.put("product_group", product_group);
        }

        return jsonDocument;
    }
}

public class CrptApi {
    private final TimeUnit timeUnit;
    private int requestLimit;
    // количество запросов сделанных сделанных в текущий промежуток времени длиной  timeInterval


    private int requestsMaden;
    // интервал в течении которого нельзя делать больше чем requestLimit запросов
    private final long timeInterval;
    private final Object lock = new Object();
    /*
    Прицип с помощью которого достигается thread-safe следующий:
    При каждом вызове createDocumentMadeInRu проверяется условие requestsMaden <= requestLimit
    Если оно не выполнено поток блокируется до тех пор пока другой поток его не разблокирует
    Если оно выполнено то выполняется отправка http запроса и после этого (блок finally)
    requestsMaden увеличивается на 1 и создается потом который спустя timeInterval ед времени уменьшит requestsMaden на 1
    таким образом для любого i верно что количество запросов сделанных за промежуток [i, i + timeInterval]
    не превышает requestLimit

     */


    public CrptApi(TimeUnit timeUnit, int requestLimit, long timeInterval) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.timeInterval = timeInterval;
    }

    private final String companyAddress = "example.ru";
    private final String url = "/api/v3/lk/documents/create";
    private final String token = "exampleToken";
    Map<Integer, String> pgMap = Map.of(1, "");

    /**
     *
     * @param document Документ в виде java объекта
     * @param signature Подпись
     * @return Ключ (value) документа если он был успешно создан
     * Полную информацию об ошибке в ином случае
     */
    public String createDocumentMadeInRu(Document document, String signature) {

        synchronized (lock) {
            if(requestsMaden > requestLimit){
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }


            JSONObject jsonDocument = document.getJsonDocument();
            jsonDocument.put("signature", signature);

            String pg = pgMap.get(Integer.parseInt(document.getProduct_group())); // не кинет искллючение на этом этапе т.к. валидация произошла на уровне getJsonDocument
            jsonDocument.put("type", "LP_INTRODUCE_GOODS");

            CloseableHttpClient httpClient = HttpClientBuilder.create().build();
            CloseableHttpResponse httpResponse = null;
            JSONObject jsonResponse = null;
            try {


                HttpPost request = new HttpPost("https://" + companyAddress + url);

                StringEntity documentData = new StringEntity(jsonDocument.toString(), ContentType.APPLICATION_JSON); // JSONObject переводит документ в Base64 так что исключения не вылетит
                request.addHeader("content-type", "application/json");
                request.addHeader("Authorization", "Bearer " + token);
                request.setEntity(documentData);
                httpResponse = httpClient.execute(request);
                String responseBody = EntityUtils.toString(httpResponse.getEntity());
                jsonResponse = new JSONObject(responseBody);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    httpClient.close();
                    if (httpResponse != null) httpResponse.close();

                    requestsMaden++;
                    //создаем поток который уменьшит requestsMaden через timeInterval
                    Thread decrementThread = new Thread(() -> {
                        try {
                            timeUnit.sleep(timeInterval);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        requestsMaden--;
                        if(requestsMaden <= requestLimit){
                            lock.notify();
                        }
                    });
                    decrementThread.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (jsonResponse.keySet().contains("value")) {

                return jsonResponse.get("value").toString();
            } else {

                return jsonResponse.get("code").toString() + "\n" +
                        jsonResponse.get("error_message").toString() + "\n" +
                        jsonResponse.get("description").toString();
            }
        }
    }
}

