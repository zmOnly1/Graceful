# graceful-shutdown
> 参考资料：[Spring Boot 内嵌容器 Tomcat / Undertow / Jetty 优雅停机实现](http://www.spring4all.com/article/1022) 

+ 需求

  当停止web应用时，应该先让已经接收到的请求执行完，并且不再接受新的请求，直到所有请求都真正返回后，再停止应用

+ 实现方式

  > 加入如下配置类

  ```java
  @Configuration
  public class ShutdownConfig {
      @Bean
      public GracefulShutdown gracefulShutdown(){
          return new GracefulShutdown();
      }
  
      @Bean
      public ServletWebServerFactory servletContainer() {
          TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
          tomcat.addConnectorCustomizers(gracefulShutdown());
          return tomcat;
      }
  
      /**
       * 优雅关闭 Spring Boot。容器必须是 tomcat
       */
      private class GracefulShutdown implements TomcatConnectorCustomizer, ApplicationListener<ContextClosedEvent> {
          private final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);
          private volatile Connector connector;
  
          @Override
          public void customize(Connector connector) {
              this.connector = connector;
          }
  
          @Override
          public void onApplicationEvent(ContextClosedEvent contextClosedEvent) {
              if(this.connector == null){
                  return;
              }
              this.connector.pause();
              Executor executor = this.connector.getProtocolHandler().getExecutor();
              if (executor instanceof ThreadPoolExecutor) {
                  ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
                  log.warn("Tomcat 正在 shutdown，如果长时间无法结束您可以强制结束");
                  threadPoolExecutor.shutdown();
                  Long startTime = System.currentTimeMillis();
                  Long lastTime = startTime;
                  Long time2Sec = 2 * 1000L;
                  while(threadPoolExecutor.isTerminating()){
                      Long currentTime = System.currentTimeMillis();
                      if(currentTime >= (lastTime + time2Sec)){
                          lastTime = currentTime;
                          log.warn("Tomcat shutdown 已执行" + (currentTime - startTime) / 1000.0 + "秒");
                      }
                  }
                  Long currentTime = System.currentTimeMillis();
                  log.warn("Tomcat shutdown 完成，用时" + (currentTime - startTime) / 1000.0 + "秒");
              }
          }
      }
  }
  ```

+ 说明

  + 不能使用`sleep`、`wait`等方法测试长时间请求，因为线程执行`shutdown`方法时，会向所有线程发送`interrupt`信号，处于阻塞态的线程会被唤醒

