package ax.ha.clouddevelopment;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.DistributionProps;
import software.amazon.awscdk.services.cloudfront.origins.S3Origin;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.BucketDeploymentProps;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

import java.util.List;

public class StaticContentBucketStack extends Stack {
    public StaticContentBucketStack(final Construct scope,
                                    final String id,
                                    final StackProps props,
                                    final String groupName) {
        super(scope, id, props);

        // A new bucket for static content.
        final Bucket bucket = new Bucket(this, "StaticContentBucket", BucketProps.builder()
                .bucketName(groupName + ".cloud-ha.com")
                .publicReadAccess(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build());

        // Adding the content of static content folder.
        new BucketDeployment(this, "DeployStaticContent", BucketDeploymentProps.builder()
                .sources(List.of(Source.asset("src/main/resources/static-content")))
                .destinationBucket(bucket)
                .build());

//        // Im trying to use the distribution inside the StaticContentBucketStack class to begin with.
//        // Probably should make this a separate class.
//        final Distribution distribution = new Distribution(this, "staticContentDistribution", DistributionProps.builder()
//                .defaultBehavior(BehaviorOptions.builder()
//                        .origin(new S3Origin(bucket))
//                        .build())
//                .build());
    }

}
