package com.example.server;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.StreamSupport;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.observation.aop.ObservedAspect;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.metrics.web.reactive.client.ObservationWebClientCustomizer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.observation.HttpRequestsObservationFilter;
import org.springframework.web.reactive.function.client.ClientObservationConvention;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@SpringBootApplication
@Import(ExemplarsConfiguration.class)
public class ServerApplication {

    private static final Logger log = LoggerFactory.getLogger(ServerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

    // tag::filter[]
    // You must set this manually until this is registered in Boot
    @Bean
    FilterRegistrationBean observationWebFilter(ObservationRegistry observationRegistry) {
        FilterRegistrationBean filterRegistrationBean = new FilterRegistrationBean(new HttpRequestsObservationFilter(observationRegistry));
        filterRegistrationBean.setDispatcherTypes(DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD,
                DispatcherType.INCLUDE, DispatcherType.REQUEST);
        filterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        // We provide a list of URLs that we want to create observations for
        filterRegistrationBean.setUrlPatterns(List.of("/user/*", "/dummy"));
        return filterRegistrationBean;
    }
    // end::filter[]

    // tag::aspect[]
    // To have the @Observed support we need to register this aspect
    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
    // end::aspect[]

//    @Bean
//    ObservationWebClientCustomizer observationWebClientCustomizer(ObservationRegistry observationRegistry, ClientObservationConvention convention) {
//        return new ObservationWebClientCustomizer(observationRegistry, convention);
//    }

    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }


    @Bean
    WebClient webClient(WebClient.Builder builder) {
      return builder
                .baseUrl("http://localhost:7654")
              .filters(exchangeFilterFunctions -> {
                  exchangeFilterFunctions.add(logRequest());
                  exchangeFilterFunctions.add(logResponse());
              })
                .build();
    }
    ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (log.isInfoEnabled()) {
                StringBuilder sb = new StringBuilder("Request: \n");
                //append clientRequest method and url
                clientRequest
                        .headers()
                        .forEach((name, values) -> {
                            sb.append(name).append(": ");
                            values.forEach(value -> sb.append(value).append(" "));
                        });
                sb.append("\n");
                log.info(sb.toString());
            }
            return Mono.just(clientRequest);
        });
    }

    ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (log.isInfoEnabled()) {
                StringBuilder sb = new StringBuilder("Response: \n");
                //append clientRequest method and url
                response
                        .headers()
                        .asHttpHeaders()
                        .forEach((name, values) -> {
                            sb.append(name).append(": ");
                            values.forEach(value -> sb.append(value).append(" "));
                        });
                sb.append("\n");
                log.info(sb.toString());
            }
            return Mono.just(response);
        });
    }
}

// tag::controller[]
@RestController
class MyController {

    private static final Logger log = LoggerFactory.getLogger(MyController.class);
    private final MyUserService myUserService;

    MyController(MyUserService myUserService) {
        this.myUserService = myUserService;
    }

    @GetMapping("/user/{userId}")
    String userName(@PathVariable("userId") String userId) {
        log.info("Got a request");
        return myUserService.userName(userId);
    }

    @GetMapping("/dummy")
    String dummy() {
        log.info("In Dummy.");
        return "Some response.";
    }
}
// end::controller[]

// tag::service[]
@Service
class MyUserService {

    private static final Logger log = LoggerFactory.getLogger(MyUserService.class);

    private final Random random = new Random();
    private final WebClient webClient;

    private final RestTemplate restTemplate;

    MyUserService(WebClient webClient, RestTemplate restTemplate) {
        this.webClient = webClient;
        this.restTemplate = restTemplate;
    }

    // Example of using an annotation to observe methods
    // <user.name> will be used as a metric name
    // <getting-user-name> will be used as a span  name
    // <userType=userType2> will be set as a tag for both metric & span
    @Observed(name = "user.name",
            contextualName = "getting-user-name",
            lowCardinalityKeyValues = {"userType", "userType2"})
    String userName(String userId) {
        log.info("Getting user name for user with id <{}>", userId);
        try {
            Mono<String> mono = webClient.get().uri("/dummy")
                    .header("foo", MDC.get("traceId"))
                    .exchangeToMono(response -> response.bodyToMono(String.class));
            String payload = mono.block();
            log.info("Got payload '{}'.", payload);

            String response = restTemplate.getForObject("http://localhost:7654/dummy", String.class);
            log.info("Got response '{}'.", response);

            Thread.sleep(random.nextLong(200L)); // simulates latency
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return "foo";
    }
}
// end::service[]

// tag::handler[]
// Example of plugging in a custom handler that in this case will print a statement before and after all observations take place
@Component
class MyHandler implements ObservationHandler<Observation.Context> {

    private static final Logger log = LoggerFactory.getLogger(MyHandler.class);

    @Override
    public void onStart(Observation.Context context) {
        log.info("Before running the observation for context [{}], userType [{}]", context.getName(), getUserTypeFromContext(context));
    }

    @Override
    public void onStop(Observation.Context context) {
        log.info("After running the observation for context [{}], userType [{}]", context.getName(), getUserTypeFromContext(context));
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }

    private String getUserTypeFromContext(Observation.Context context) {
        return StreamSupport.stream(context.getLowCardinalityKeyValues().spliterator(), false)
                .filter(keyValue -> "userType".equals(keyValue.getKey()))
                .map(KeyValue::getValue)
                .findFirst()
                .orElse("UNKNOWN");
    }
}
// end::handler[]
