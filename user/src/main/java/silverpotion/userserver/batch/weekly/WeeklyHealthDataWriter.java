package silverpotion.userserver.batch.weekly;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import silverpotion.userserver.healthData.domain.HealthData;
import silverpotion.userserver.healthData.reopisitory.HealthDataRepository;

@Component
public class WeeklyHealthDataWriter implements ItemWriter<HealthData> {

    private final HealthDataRepository healthDataRepository;

    public WeeklyHealthDataWriter(HealthDataRepository healthDataRepository) {
        this.healthDataRepository = healthDataRepository;
    }

    @Override
    //청크는 리스트 같은 하나의 묶음. 우리가 config에서 설정한 크기. 여기선 100개 단위로 처리. 즉 IteamReader->Processor해서 100번 작업하여 100개의 HealthData가 만들어지면 하나의 청크가 묶인다.
    //하나의 청크가 생기면 자동으로 write()가 호출되어 100개의 HealthData가 자동으로 db에 저장된다.

    public void write(Chunk<? extends HealthData> chunk) throws Exception {
            healthDataRepository.saveAll(chunk);
            healthDataRepository.flush(); // 현재 전체 job에서 이 주간데이터가 다 만들어져도 db에는 바로 저장이 안되서 주간데이터를 기반으로 주간리포트를 만드는 다음 스탭에서 에러가 남. 따라서 flush 는 db로 즉시 쿼리를 날리게 함
    }
}
