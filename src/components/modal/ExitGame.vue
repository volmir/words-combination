<template>
    <div>
    
<div class="modal fade" id="exitModal" tabindex="-1" role="dialog" aria-labelledby="exitModal" aria-hidden="true">
  <div class="modal-dialog" role="document">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="exampleModalLabel">Вы уверены, что хотите завершить игру?</h5>
        <button type="button" class="close" data-dismiss="modal" aria-label="Закрыть">
          <span aria-hidden="true">&times;</span>
        </button>
      </div>
      <div class="modal-body">
                        <p class="text-center">          
                            <br>    
                            <a href="/game/finish" class="btn btn-danger margin-right"> Да </a>
                            <button type="button" class="btn btn-success margin-right" data-dismiss="modal">Нет</button>
                            <br>
                        </p>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-outline-secondary" data-dismiss="modal">Закрыть</button>
      </div>
    </div>
  </div>
</div> 
        

    </div>
</template>

<script>
export default {
    
}
</script>