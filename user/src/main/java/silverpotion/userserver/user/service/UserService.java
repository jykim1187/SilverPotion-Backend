package silverpotion.userserver.user.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.Transient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import silverpotion.userserver.admin.domain.Admin;
import silverpotion.userserver.admin.repository.AdminRepository;
import silverpotion.userserver.careRelation.domain.CareRelation;
import silverpotion.userserver.careRelation.domain.LinkStatus;
import silverpotion.userserver.common.auth.JwtTokenProvider;
import silverpotion.userserver.common.dto.UserBanedException;
import silverpotion.userserver.fireBase.service.FireBaseService;
import silverpotion.userserver.payment.domain.CashItem;
import silverpotion.userserver.payment.dtos.CashItemOfPaymentListDto;
import silverpotion.userserver.user.domain.BanYN;
import silverpotion.userserver.user.domain.DelYN;
import silverpotion.userserver.user.domain.Role;
import silverpotion.userserver.user.domain.User;
import silverpotion.userserver.user.dto.*;
import silverpotion.userserver.user.repository.UserRepository;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    @Qualifier("refreshToken")
    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    @Value("${jwt.secretKeyRt}")
    private String secretKeyRt;
    @Value("${cloud.aws.s3.bucket}")
    private String bucket;
    private final S3Client s3Client;
    private final AdminRepository adminRepository;
    private final FireBaseService fireBaseService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, RedisTemplate<String, Object> redisTemplate, JwtTokenProvider jwtTokenProvider, S3Client s3Client, AdminRepository adminRepository, FireBaseService fireBaseService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
        this.jwtTokenProvider = jwtTokenProvider;
        this.s3Client = s3Client;
        this.adminRepository = adminRepository;
        this.fireBaseService = fireBaseService;
    }

    // 1.회원가입
    public Long userCreate(UserCreateDto dto) {
        if (userRepository.findByLoginIdAndDelYN(dto.getLoginId(), DelYN.N).isPresent()) {
            throw new IllegalArgumentException("이미 사용중인 로그인 아이디입니다");
        }
        if (userRepository.findByNickNameAndDelYN(dto.getNickName(), DelYN.N).isPresent()) {
            throw new IllegalArgumentException("이미 사용중인 닉네임입니다");
        }
        if (userRepository.findByEmailAndDelYN(dto.getEmail(), DelYN.N).isPresent()) {
            throw new IllegalArgumentException("이미 사용중인 이메일입니다");
        }

        //      인증 로직 구현 후에 dto의 암호화된 패스워드로 변경해야 함
        User user = userRepository.save(dto.toEntity(passwordEncoder.encode(dto.getPassword())));
        return user.getId();
    }

    //1-2.회원가입 중복체크
    public boolean isDuplicate(String field, String value) {
        if ("loginId".equals(field)) {
            return userRepository.existsByLoginId(value);
        } else if ("email".equals(field)) {
            return userRepository.existsByEmail(value);
        } else if ("nickName".equals(field)) {
            return userRepository.existsByNickName(value);
        } else {
            throw new IllegalArgumentException("잘못된 입력입니다");
        }
    }

    //    2-1.로그인
    public Map<String, Object> login(LoginDto dto) {

        User user = userRepository.findByLoginIdAndDelYN(dto.getLoginId(), DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 사용자입니다"));
        String adminRole = null;
        if (!user.getRole().equals(Role.USER)) {
            Admin admin = adminRepository.findByUserId(user.getId()).orElseThrow(() -> new EntityNotFoundException("Admin Not Found"));
            adminRole = (admin.getRole() != null) ? admin.getRole().toString() : "ROLE_NONE";
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
        }
        // 정지 해제 조건 확인
        if (user.getBanYN().equals(BanYN.N) && user.getBanUntil() != null && user.getBanUntil().isBefore(LocalDateTime.now())) {
            user.unban();
            userRepository.save(user);
        }

        //정지 상태라면 로그인 차단
        if (user.getBanYN().equals(BanYN.Y)) {
            throw new UserBanedException("정지된 계정입니다. 해제 시각:" + user.getBanUntil());
        }

        String jwtToken = jwtTokenProvider.createToken(
                user.getLoginId(), user.getRole().toString(), user.getId(), user.getProfileImage(), user.getNickName(), user.getName(), adminRole);
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getLoginId(), user.getRole().toString());
        redisTemplate.opsForValue().set(user.getLoginId(), refreshToken, 200, TimeUnit.DAYS);
        Map<String, Object> loginInfo = new HashMap<>();

        loginInfo.put("userId", user.getId());
        loginInfo.put("role", user.getRole());
        loginInfo.put("adminRole", adminRole);
        loginInfo.put("profileUrl", user.getProfileImage());
        loginInfo.put("nickName", user.getNickName());
        loginInfo.put("name", user.getName());
        loginInfo.put("id", user.getLoginId());
        loginInfo.put("token", jwtToken);
        loginInfo.put("refreshToken", refreshToken);


        return loginInfo;


    }

    //    2-2.로그인(리프레쉬토큰 재발급)
    public Map<String, Object> recreateAccessToken(UserRefreshDto dto) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(secretKeyRt)
                .build()
                .parseClaimsJws(dto.getRefreshToken())
                .getBody();

        Map<String, Object> loginInfo = new HashMap<>();
        Object refreshTokenOfDto = redisTemplate.opsForValue().get(claims.getSubject());
        if (refreshTokenOfDto == null || !refreshTokenOfDto.toString().equals(dto.getRefreshToken())) {
            loginInfo.put("token", "fail");
            return loginInfo;
        } //레디스에 리프레시토큰 값이 없었거나 사용자의 리프레시토큰갑과 일치 안하니 accesstoken발급 하지않는다.(그래서 token값에 fail세팅)

        String token = jwtTokenProvider.createToken(claims.getSubject(), claims.get("role").toString(), Long.parseLong(claims.get("userId").toString()), claims.get("profileUrl").toString(), claims.get("nickName").toString(), claims.get("name").toString(), claims.get("adminRole").toString());
        loginInfo.put("token", token);
        return loginInfo;
    }

    public void logout(String loginId) {
        String redisKey = loginId;  // 이미 loginId 자체가 key로 들어가 있음
        if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
            redisTemplate.delete(redisKey);
            System.out.println("RefreshToken 삭제 완료 - loginId : " + loginId);
        } else {
            System.out.println("저장된 RefreshToken 없음 - loginId : " + loginId);
        }
    }

    //2.3 로그인 아이디로 유저찾기 feign 용
    public User getUserByLoginId(String loginId) {
        return userRepository.findByLoginIdAndDelYN(loginId, DelYN.N)
                .orElseThrow(() -> new EntityNotFoundException("유저를 찾을 수 없습니다."));
    }

    //    3.정보 업데이트
    public Long update(UserUpdateDto dto, String loginId) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다"));
        String newPw = null;
        if (dto.getPassword() != null) {
            newPw = passwordEncoder.encode(dto.getPassword());
        }
        user.updateUser(dto, newPw);
        return user.getId();
    }

    //    4.내 정보 조회(마이페이지)
    public UserMyPageDto userMyPage(String loginId) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다"));
        return user.toMyPageDtoFromEntity(user.findNameFromDependentList(), user.findNameFromProtectorsList());
    }

    //    5.내 피보호자 조회
    public List<UserLinkedUserDto> whoMyDependents(String loginId) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다"));
        List<CareRelation> dependents = user.getAsProtectors(); // 내 피보호자는 내가 보호자로 맺은 관계속에 있으니까
        return dependents.stream().filter(c -> c.getLinkStatus() == LinkStatus.CONNECTED).map(c -> c.getDependent().toLinkUserDtoFromEntity()).toList();

    }

    //    6.내 보호자 조회
    public List<UserLinkedUserDto> whoMyProtectors(String loginId) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다"));
        List<CareRelation> protectors = user.getAsDependents(); //내 보호자는 내가 피보호자로 맺은 관계속에 있으니까
        return protectors.stream().filter(c -> c.getLinkStatus() == LinkStatus.CONNECTED).map(c -> c.getProtector().toLinkUserDtoFromEntity()).toList();

    }

    //    7.loginId로 userId조회하기
    public Long getUserIdByLoginId(String loginId) {
        User user = userRepository.findByLoginId(loginId).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다."));
        return user.getId();
    }

    //    8.loginId로 userId와 nickname 조회하기
    public UserProfileInfoDto getUserProfileInfo(String loginId) {
        User user = userRepository.findByLoginId(loginId).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다."));
        Long userId = user.getId();
        String nickname = user.getNickName();
        String profileImage = user.getProfileImage();
        String street = user.getAddress();
        return UserProfileInfoDto.userProfileInfoDto(userId, nickname, profileImage, street);
    }

    //    8.userId로 userId와 nickname 조회하기
    public UserProfileInfoDto getUserProfileInfo(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다."));
        String nickname = user.getNickName();
        String profileImage = user.getProfileImage();
        System.out.println("profileImage(user):"+profileImage);
        String street = user.getAddress();
        return UserProfileInfoDto.userProfileInfoDto(userId, nickname, profileImage, street);
    }


    //  9. 유저 목록 조회
    public List<UserListDto> findAll(UserListDto dto) {
        List<User> users = userRepository.findAll();
        return users.stream().map(UserListDto::fromEntity).collect(Collectors.toList());
    }

    //  feign용 getNickNameByUserId
    public User getNickNameByUserId(Long id) {
        return userRepository.findByIdAndDelYN(id, DelYN.N).orElseThrow(() -> new EntityNotFoundException("not found user"));
    }

    // feign용 userId로 loginId 조회하기
    public User getLoginIdByUserId(Long userId) {
        return userRepository.findByIdAndDelYN(userId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("not found user"));
    }

    // 11.프로필 이미지 등록 및 수정
    public String postProfileImage(String loginId, UserProfileImgDto dto) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 유저입니다"));
        MultipartFile image = dto.getImage();
        String fileNmae = "profile/" + user.getLoginId() + "-" + "profile - " + image.getOriginalFilename();
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(fileNmae)
                .build();
        // 로컬 저장없이 바로 s3로. putObject는 s3에 객체를 업로드하는 메서드로 두가지 인자를 받음
        //putObjectRequest는 업로할 파일의 정보. RequestBody는 업로드할 파일의 실제 내용이고 .fromInputStrem은 MultipartFile이 가진 파일 내용을
        //직접 스트림으로 꺼내서 s3로 넘기는 방식으로 우리가 수업에서 배운 바이트 배열로 받는 형식보다 큰 파일을 다루는데 있어 더 효율적임. 그리고 추가로 image의 크기도 미리 알려줘야함
        String s3Url = "";
        try {
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(image.getInputStream(), image.getSize()));
            s3Url = s3Client.utilities().getUrl(a -> a.bucket(bucket).key(fileNmae)).toExternalForm();
            user.changeMyProfileImag(s3Url);
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("s3 이미지 업로드 실패");
        }
        return s3Url;
    }

    //12. 상대프로필 조회
    public UserProfileInfoDto yourProfile(Long id) {
        User user = userRepository.findByIdAndDelYN(id, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        return user.profileInfoDtoFromEntity();
    }

    //    12. 특정 유저 프로필 리스트 조회
    public List<UserListDto> getUsersByIds(List<Long> userIds) {
        // 빈 리스트나 null이 들어오는 경우 방어
        if (userIds == null || userIds.isEmpty()) {
            throw new IllegalArgumentException("유저 ID 리스트가 비어 있습니다.");
        }

        // userIds로 DB에서 유저 리스트 조회
        List<User> users = userRepository.findAllByIdInAndDelYN(userIds, DelYN.N);
        System.out.println(users);

        // User 엔티티 → UserListDto로 변환
        List<UserListDto> userListDtos = new ArrayList<>();
        for (User user : users) {
            UserListDto dto = user.ListDtoFromEntity();
            userListDtos.add(dto);
        }
        System.out.println(userListDtos);

        return userListDtos;
    }

    //    14. 내 결제내역 조회하기
    public List<CashItemOfPaymentListDto> getMyPayments(String loginId) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        List<CashItem> payments = user.getMyPaymentList();
        return payments.stream().map(c -> c.ListDtoFromEntity()).toList();
    }

    //15. 내 보유한 힐링포션 조회
    public int getMyPotion(String loginId) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        return user.howManyPotion();
    }

    //16. 로그인 아이디 받으면 해당 아이디를 가진 유저의 이름과 닉네임반환(화상채팅 화면용 api)
    public List<String> getMyNames(String loginId, UserReturnNameInfoDto dto) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        User Opponent = userRepository.findByLoginIdAndDelYN(dto.getOpponentId(), DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        List<String> nameInfo = new ArrayList<>();
        nameInfo.add(Opponent.getName());
        nameInfo.add(Opponent.getNickName());
        return nameInfo;

    }

    //17. 화면에서 로그인 아이디 주면 프로필 이미지 주는 용도
    public String getProfilePicture(String loginId, UserImgReqDto dto) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        User selectedUser;
        if (loginId.equals(dto.getLoginId())) {
            selectedUser = user;
        } else {
            selectedUser = userRepository.findByLoginIdAndDelYN(dto.getLoginId(), DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        }
        return selectedUser.getProfileImage();
    }

    //18.로그인 아이디 주면 이 사람이 건강세부조사 작성했는지 아닌지 여부 리턴
    public boolean haveDetailHealthInfo(String loginId, UserHaveDetailHealthInfoReqDto dto) {
        boolean yesOrNo = false;
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        User selectedUser;
        if (loginId.equals(dto.getLoginId())) {
            selectedUser = user;
        } else {
            selectedUser = userRepository.findByLoginIdAndDelYN(dto.getLoginId(), DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        }

        if (selectedUser.getUserDetailHealthInfo() != null) {
            yesOrNo = true;
        }
        return yesOrNo;
    }


    //    게시물 조회시, 작성자 프로필 조회
    public Map<Long, UserProfileInfoDto> getProfileInfoMap(List<Long> userIds) {
        List<User> users = userRepository.findAllById(userIds); // JPA 기본 제공
        return users.stream()
                .collect(Collectors.toMap(
                        User::getId,
                        user -> UserProfileInfoDto.userProfileInfoDto(
                                user.getId(),
                                user.getNickName(),
                                user.getProfileImage(),
                                user.getAddress()
                        )
                ));
    }


    //   sns로그인 oauth만들기
    public User userBySocialId(String loginId) {
        User user = userRepository.findByLoginId(loginId).orElse(null);
        return user;
    }

    //    회원탈퇴
    public String withdraw(String loginIg) {
        User user = userRepository.findByLoginIdAndDelYN(loginIg, DelYN.N).orElseThrow(() -> new EntityNotFoundException("없는 회원입니다"));
        user.withdraw();
        return user.getNickName();
    }

    //    비밀번호 변경
    @Transactional
    public Long changePassword(String loginId, ChangePasswordDto dto) {
        User user = userRepository.findByLoginId(loginId).orElseThrow(() -> new EntityNotFoundException("없는 사용자"));
        //본인인증 확인
        if (!user.getLoginId().equals(loginId)) {
            throw new SecurityException("비밀번호 변경 권한이 없습니다");
        }
//  현재 비밀번호 확인
        System.out.println(dto.getCurrentPassword());
        System.out.println(user.getPassword());

        if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호와 일치하지 않습니다.");
        }

        // 새 비밀번호 정책 검증 (예: 8자 이상, 숫자/특수문자 포함)
        if (!isValidPassword(dto.getNewPassword())) {
            throw new IllegalArgumentException("비밀번호가 보안 정책을 충족하지 않습니다.");
        }
        // 비밀번호 변경
        user.changePassword(dto.getNewPassword(), passwordEncoder);
        return user.getId();
    }

    //   8. 비밀번호 검증
    private boolean isValidPassword(String password) {
        return password.length() >= 8 && password.matches(".*[0-9].*") && password.matches(".*[!@#$%^&*()].*");
    }

    // id 로 유저 찾기 feign
    public UserDto getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("해당 ID의 사용자를 찾을 수 없습니다."));

        return UserDto.builder()
                .id(user.getId())
                .loginId(user.getLoginId())
                .nickName(user.getNickName())
                .birthday(user.getBirthday())
                .name(user.getName())
                .build();
    }

    //화상채팅 알람용
    public void sendVedioCallNotification(String loginId) {
        User user = userRepository.findByLoginIdAndDelYN(loginId, DelYN.N).orElseThrow(() -> new EntityNotFoundException("유저 없음"));

        fireBaseService.sendVideoCallNotification(user.getFireBaseToken(), user.getName());
        System.out.println("파이어베이스 화상채팅 알림 호출");

    }


    // 게시물 좋아요 유저 목록 조회
    public List<UserListDto> getPostLikeList(List<Long> userIds) {
        //게시물 좋아요 유저 목록
        List<User> users = userRepository.findAllById(userIds);
        return users.stream().map(UserListDto::fromEntity).collect(Collectors.toList());
    }


    }

