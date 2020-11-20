FROM openshift/jenkins-agent-el-cicd-base

USER root

RUN yum-config-manager --disable cbs-paas7-openshift-multiarch-el7-build && \
    yum install -y --setopt=tsflags=nodocs ShellCheck openssl-devel libcurl libcurl-devel libpng-devel mesa-libGL-devel mesa-libGLU-devel libpng-devel libxml2 libxml2-devel && \
    yum install -y R && \
    yum  clean all && \
    R -e "install.packages('devtools', repos='https://cran.rstudio.com/')"  && \
    R -e "install.packages('renv', repos='https://cran.rstudio.com/')"  && \
    R -e "install.packages('lintr', repos='https://cran.rstudio.com/')"  && \
    R -e "install.packages('jsonlite', repos='https://cran.rstudio.com/')"

USER 1001