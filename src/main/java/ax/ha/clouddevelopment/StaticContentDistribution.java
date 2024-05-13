//package ax.ha.clouddevelopment;
//
//import software.amazon.awscdk.Stack;
//import software.amazon.awscdk.StackProps;
//import software.amazon.awscdk.services.cloudfront.CloudFrontWebDistribution;
//import software.amazon.awscdk.services.cloudfront.CloudFrontWebDistributionProps;
//import software.amazon.awscdk.services.cloudfront.S3OriginConfig;
//import software.amazon.awscdk.services.cloudfront.SourceConfiguration;
//import software.constructs.Construct;
//import software.amazon.awscdk.services.s3.Bucket;
//
//import java.util.List;
//
//public class StaticContentDistribution extends Stack {
//    public StaticContentDistribution(final Construct scope,
//                                     final String id,
//                                     final StackProps props,
//                                     final String bucketDomainName) {
//        super(scope, id, props);
//
//        CloudFrontWebDistribution distribution = new CloudFrontWebDistribution(this, "MyDistribution",
//                CloudFrontWebDistributionProps.builder()
//                        .originConfigs(List.of(
//                                SourceConfiguration.builder()
//                                        .s3OriginSource(S3OriginConfig.builder()
//                                                .s3BucketSource(Bucket
//                                                        .fromBucketName(this, "StaticContentBucket", bucketDomainName))
//                                                .build())
//                                        .build()
//                        ))
//                        .build());
//    }
//}
